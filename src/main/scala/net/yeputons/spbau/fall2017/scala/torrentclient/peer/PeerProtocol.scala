package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import java.nio.{BufferUnderflowException, ByteBuffer, ByteOrder}

import akka.NotUsed
import akka.stream.scaladsl.{BidiFlow, Flow, Framing, Keep, Source}
import akka.util.{ByteString, ByteStringBuilder}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerHandshake.HandshakeCompleted

import scala.concurrent.Future
import scala.util.{Failure, Success}

sealed trait PeerMessage
object PeerMessage {
  case object KeepAlive extends PeerMessage

  case object Choke extends PeerMessage
  case object Unchoke extends PeerMessage
  case object Interested extends PeerMessage
  case object NotInterested extends PeerMessage
  case class HasNewPiece(pieceId: Int) extends PeerMessage
  case class HasPieces(hasPieces: Set[Int]) extends PeerMessage
  case class PieceRequest(piece: PieceId) extends PeerMessage
  case class PieceAvailable(piece: PieceId, data: ByteString)
      extends PeerMessage
  case class PieceRequestCancel(piece: PieceId) extends PeerMessage

  case class PieceId(index: Int, begin: Int, length: Int)
}

object PeerProtocol {
  def apply(infoHash: ByteString,
            myPeerId: ByteString,
            otherPeerId: Option[ByteString])
    : BidiFlow[PeerMessage,
               ByteString,
               ByteString,
               PeerMessage,
               Future[HandshakeCompleted.type]] =
    PeerMessagesParsing()
      .atop(PeerFraming())
      .atopMat(PeerHandshake(infoHash, myPeerId, otherPeerId))(Keep.right)
}

object PeerMessagesParsing {
  import PeerMessage._

  def apply()
    : BidiFlow[PeerMessage, ByteString, ByteString, PeerMessage, NotUsed] =
    BidiFlow.fromFunctions(encodeMessage, decodeMessage)

  private implicit val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN

  def encodeMessage(message: PeerMessage): ByteString = message match {
    case KeepAlive     => ByteString.empty
    case Choke         => ByteString(0)
    case Unchoke       => ByteString(1)
    case Interested    => ByteString(2)
    case NotInterested => ByteString(3)
    case HasNewPiece(id) =>
      ByteString.newBuilder.putByte(4).putInt(id).result()
    case HasPieces(pieces) =>
      val maxId = if (pieces.isEmpty) 0 else pieces.max
      val bitmask = (0 until (maxId + 7) / 8).map { position =>
        var result = 0
        for (i <- 0 to 7)
          if (pieces.contains(8 * position + i))
            result |= 1 << (7 - i)
        result.toByte
      }.toArray
      ByteString(5) ++ ByteString(bitmask)
    case PieceRequest(pieceId) =>
      ByteString.newBuilder
        .putByte(6)
        .putPieceId(pieceId)
        .result()
    case PieceAvailable(pieceId, data) =>
      ByteString.newBuilder
        .putByte(7)
        .putPieceId(pieceId)
        .result() ++ data
    case PieceRequestCancel(pieceId) =>
      ByteString.newBuilder
        .putByte(8)
        .putPieceId(pieceId)
        .result()
  }

  def decodeMessage(message: ByteString): PeerMessage =
    if (message.isEmpty) KeepAlive
    else {
      val buffer = message.asByteBuffer
      buffer.order(byteOrder)
      try {
        val result = buffer.get() match {
          case 0 => Choke
          case 1 => Unchoke
          case 2 => Interested
          case 3 => NotInterested
          case 4 => HasNewPiece(buffer.getInt())
          case 5 =>
            HasPieces(
              ByteString(buffer).zipWithIndex.flatMap {
                case (byte, position) =>
                  for {
                    i <- 0 to 7
                    if (byte & (1 << (7 - i))) != 0
                  } yield 8 * position + i
              }.toSet
            )
          case 6 => PieceRequest(buffer.getPieceId())
          case 7 => PieceAvailable(buffer.getPieceId(), ByteString(buffer))
          case 8 => PieceRequestCancel(buffer.getPieceId())
          case _ =>
            throw PeerProtocolDecodeException("Unknown type id", message)
        }
        if (buffer.remaining() > 0) {
          throw PeerProtocolDecodeException(
            "Unexpected trailing data in the message",
            message)
        }
        result
      } catch {
        case _: BufferUnderflowException =>
          throw PeerProtocolDecodeException("Unexpected end of message",
                                            message)
      }
    }

  private implicit class ByteStringPieceIdPutter(val builder: ByteStringBuilder)
      extends AnyVal {
    def putPieceId(piece: PieceId): ByteStringBuilder =
      builder
        .putInt(piece.index)
        .putInt(piece.begin)
        .putInt(piece.length)
  }

  private implicit class ByteBufferPieceIdGetter(
      val buffer: ByteBuffer
  ) extends AnyVal {
    //noinspection AccessorLikeMethodIsEmptyParen
    // Mimicking similar Java API
    def getPieceId(): PieceId =
      PieceId(buffer.getInt(), buffer.getInt(), buffer.getInt())
  }
}

case class PeerProtocolDecodeException(message: String, frame: ByteString)
    extends Exception(s"$message in frame $frame")

object PeerFraming {
  final val MaximalFrameSize = 1 << 24

  def apply()
    : BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
    Framing.simpleFramingProtocol(MaximalFrameSize)
}

object PeerHandshake {
  private final val Header: ByteString =
    ByteString(19) ++ ByteString("BitTorrent protocol") ++
      ByteString(0, 0, 0, 0, 0, 0, 0, 0)

  def apply(infoHash: ByteString,
            myPeerId: ByteString,
            otherPeerId: Option[ByteString])
    : BidiFlow[ByteString,
               ByteString,
               ByteString,
               ByteString,
               Future[HandshakeCompleted.type]] = {
    require(infoHash.length == 20, "infoHash should be exactly 20 bytes")
    require(myPeerId.length == 20, "myPeerId should be exactly 20 bytes")
    otherPeerId.foreach(x =>
      require(x.length == 20, "otherPeerId should be exactly 20 bytes"))

    val localToRemoteFlow =
      Flow[ByteString].prepend(Source.single(Header ++ infoHash ++ myPeerId))
    val remoteToLocalFlow =
      ExpectPrefixFlow[Byte, ByteString, HandshakeCompleted.type](
        Header.length + 40, { prefix: ByteString =>
          val (realHeader, tail) = prefix.splitAt(Header.size)
          val (realInfoHash, realOtherPeerId) = tail.splitAt(infoHash.size)
          if (realHeader != Header)
            Failure(InvalidPartException("header", realHeader, Header))
          else if (realInfoHash != infoHash)
            Failure(InvalidPartException("info_hash", realInfoHash, infoHash))
          else if (!otherPeerId.forall(_ == realOtherPeerId))
            Failure(
              InvalidPartException("otherPeerId",
                                   realOtherPeerId,
                                   otherPeerId.get))
          else
            Success(())
        },
        HandshakeCompleted
      )
    BidiFlow.fromFlowsMat(localToRemoteFlow, remoteToLocalFlow)(Keep.right)
  }

  case object HandshakeCompleted

  case class HandshakeException(message: String) extends Exception(message)
  case class InvalidPartException(part: String,
                                  real: ByteString,
                                  expected: ByteString)
      extends Exception(s"invalid $part: got $real instead of $expected")
}
