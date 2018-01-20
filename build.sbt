name := "au-scala-project-fall-2017"

version := "0.1"

scalaVersion := "2.12.4"
scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation", "-Xfatal-warnings")
libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.6",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.codehaus.janino" % "janino" % "3.0.8",
  "com.typesafe.akka" %% "akka-actor" % "2.5.9",
  "com.typesafe.akka" %% "akka-slf4j" % "2.5.9",
  "com.typesafe.akka" %% "akka-stream" % "2.5.9",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.9" % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.9" % Test,
  "com.typesafe.akka" %% "akka-http-core" % "10.1.0-RC1",
  "org.scalatest" %% "scalatest" % "3.0.4" % Test,
  "org.scalamock" %% "scalamock" % "4.0.0" % Test,
)