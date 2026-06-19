ThisBuild / organization := "sk.superfaktura"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.7.4"

ThisBuild / scalacOptions := Seq(
  "-deprecation",
  "-feature",
  "-Werror",
  "-Wunused:all",
  "-Wvalue-discard"
)

val catsEffectV = "3.6.3"
val catsV = "2.13.0"
val scodecBitsV = "1.2.5"
val http4sV = "0.23.34"
val circeV = "0.14.15"
val fs2V = "3.13.0"
val fs2DataV = "1.14.0"
val declineV = "2.6.2"
val pureconfigV = "0.17.10"
val scalatestV = "3.2.20"

lazy val core = (project in file("core"))
  .settings(
    name := "superfaktura-batteries-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsV,
      "org.scodec" %% "scodec-bits" % scodecBitsV,
      "io.circe" %% "circe-core" % circeV,
      "io.circe" %% "circe-generic" % circeV,
      "org.scalatest" %% "scalatest" % scalatestV % Test
    )
  )

lazy val cli = (project in file("cli"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "superfaktura-batteries-cli",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectV,
      "org.http4s" %% "http4s-ember-client" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "io.circe" %% "circe-core" % circeV,
      "io.circe" %% "circe-parser" % circeV,
      "io.circe" %% "circe-generic" % circeV,
      "org.gnieh" %% "fs2-data-csv" % fs2DataV,
      "co.fs2" %% "fs2-io" % fs2V,
      "com.monovore" %% "decline" % declineV,
      "com.monovore" %% "decline-effect" % declineV,
      "com.github.pureconfig" %% "pureconfig-core" % pureconfigV,
      "com.github.pureconfig" %% "pureconfig-generic-scala3" % pureconfigV,
      "org.scalatest" %% "scalatest" % scalatestV % Test
    )
  )

lazy val root = (project in file("."))
  .aggregate(core, cli)
  .settings(
    name := "superfaktura-batteries",
    publish / skip := true
  )
