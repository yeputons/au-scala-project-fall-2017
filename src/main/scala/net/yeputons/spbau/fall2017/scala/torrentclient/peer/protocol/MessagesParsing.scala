package net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol

import java.nio.{BufferUnderflowException, ByteBuffer, ByteOrder}

import akka.NotUsed
import akka.stream.scaladsl.BidiFlow
import akka.util.{ByteString, ByteStringBuilder}

object MessagesParsing {
  import PeerMessage._

  /**
    * Creates [[BidiFlow]] which implements BitTorrent peer protocol.
    * `I1` and `O2` are streams of [[PeerMessage]] and `I2`/`O1` are
    * frames, which should be further translated into TCP bytes via
    * [[Framing]].
    *
    * @return
    */
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
    case BlockRequest(blockId) =>
      ByteString.newBuilder
        .putByte(6)
        .putBlockId(blockId)
        .result()
    case BlockAvailable(blockId, data) =>
      ByteString.newBuilder
        .putByte(7)
        .putBlockId(blockId)
        .result() ++ data
    case BlockRequestCancel(blockId) =>
      ByteString.newBuilder
        .putByte(8)
        .putBlockId(blockId)
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
          case 6 => BlockRequest(buffer.getBlockId())
          case 7 => BlockAvailable(buffer.getBlockId(), ByteString(buffer))
          case 8 => BlockRequestCancel(buffer.getBlockId())
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

  private implicit class ByteStringBlockIdPutter(val builder: ByteStringBuilder)
      extends AnyVal {
    def putBlockId(block: BlockId): ByteStringBuilder =
      builder
        .putInt(block.pieceId)
        .putInt(block.begin)
        .putInt(block.length)
  }

  private implicit class ByteBufferBlockIdGetter(
      val buffer: ByteBuffer
  ) extends AnyVal {
    //noinspection AccessorLikeMethodIsEmptyParen
    // Mimicking similar Java API
    def getBlockId(): BlockId =
      BlockId(buffer.getInt(), buffer.getInt(), buffer.getInt())
  }
}
