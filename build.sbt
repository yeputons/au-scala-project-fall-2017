name := "au-scala-project-fall-2017"

version := "0.1"

scalaVersion := "2.12.4"
scalacOptions += "-feature"
libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.6",
  "com.typesafe.akka" %% "akka-actor" % "2.5.9",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.9" % Test,
  "com.typesafe.akka" %% "akka-http-core" % "10.0.9",
)