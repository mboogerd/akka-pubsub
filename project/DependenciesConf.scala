import sbt.Keys._
import sbt._


object DependenciesConf {

  lazy val scala: Seq[Setting[_]] = Seq(
    scalaVersion := "2.12.4",
    resolvers ++= Seq(
      Resolver.jcenterRepo,
      Resolver.sonatypeRepo("releases")
    )
  )

  lazy val common: Seq[Setting[_]] = scala ++ Seq(
    libraryDependencies ++= commonDeps
  )

  def commonDeps = {
    val akkaVersion = "2.5.6"

    Seq(
      // logging
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "org.log4s" %% "log4s" % "1.4.0",

      // akka
      "com.typesafe.akka" %% "akka-remote" % akkaVersion, // (includes transitively all we need)
      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,

      // typelevel
      "com.github.pureconfig" %% "pureconfig" % "0.8.0",
      "org.typelevel" %% "cats" % "0.9.0",

      // test
      "org.scalatest" %% "scalatest" % "3.2.0-SNAP9" % Test,
      "org.scalacheck" %% "scalacheck" % "1.13.5" % Test,
      "org.scalamock" %% "scalamock-scalatest-support" % "3.6.0" % Test,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion % Test
    )
  }
}