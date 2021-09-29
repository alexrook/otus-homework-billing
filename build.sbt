organization := "homework"
name := "billing"

scalaVersion := "2.13.6"

lazy val logback: Seq[ModuleID] = {
  val V = "1.2.6"
  Seq(
    "ch.qos.logback" % "logback-classic" % V
  )
}

lazy val akkaTyped: Seq[ModuleID] = {
  val V = "2.6.16"
  Seq(
    "com.typesafe.akka" %% "akka-actor-typed"         % V,
    "com.typesafe.akka" %% "akka-persistence-typed"   % V,
    "com.typesafe.akka" %% "akka-persistence-query"   % V,
    "com.typesafe.akka" %% "akka-stream"              % V,
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % V % Test
  )
}

lazy val akkaHttp: Seq[ModuleID] = {
  lazy val V = "10.2.6"
  Seq(
    "com.typesafe.akka" %% "akka-http"            % V,
    "com.typesafe.akka" %% "akka-http-spray-json" % V,
    "com.typesafe.akka" %% "akka-http-testkit"    % V % Test
  )
}

lazy val circe = {
  val V = "0.14.1"
  Seq(
    "io.circe" %% "circe-core"    % V,
    "io.circe" %% "circe-generic" % V,
    //"io.circe" %% "circe-parser" % V,
    "de.heikoseeberger" %% "akka-http-circe" % "1.38.2"
  )
}

lazy val levelDb = {
  lazy val leveldbVersion    = "0.7"
  lazy val leveldbjniVersion = "1.8"
  Seq(
    "org.iq80.leveldb"          % "leveldb"        % leveldbVersion,
    "org.fusesource.leveldbjni" % "leveldbjni-all" % leveldbjniVersion
  )
}

lazy val alpakka = {
  val V = "3.0.3"
  Seq(
    "com.lightbend.akka" %% "akka-stream-alpakka-slick" % V
  )
}

lazy val flywayDb = {
  val V = "7.3.2"
  Seq(
    "org.flywaydb" % "flyway-core" % V
  )
}

lazy val postgreSql ={
  val V="42.2.24"
  Seq(
    "org.postgresql" % "postgresql" %  V
  )
}

lazy val scalatest: ModuleID = "org.scalatest" %% "scalatest" % "3.2.10" % Test

libraryDependencies ++= akkaTyped
libraryDependencies ++= akkaHttp
libraryDependencies ++= alpakka
libraryDependencies ++= levelDb
libraryDependencies ++= postgreSql
libraryDependencies ++= flywayDb
libraryDependencies ++= circe
libraryDependencies ++= logback
libraryDependencies += scalatest
