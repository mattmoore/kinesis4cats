import sbt._
import sbt.Keys._

import org.typelevel.sbt._
import org.typelevel.sbt.gha._
import LibraryDependencies._
import org.scalafmt.sbt.ScalafmtPlugin

object Kinesis4CatsPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires: Plugins = TypelevelPlugin

  def mkCommand(commands: List[String]): String =
    commands.mkString("; ", "; ", "")

  val autoImport: Kinesis4CatsPluginKeys.type = Kinesis4CatsPluginKeys
  import autoImport._

  import TypelevelVersioningPlugin.autoImport._
  import TypelevelGitHubPlugin.autoImport._
  import TypelevelKernelPlugin.autoImport._
  import TypelevelCiPlugin.autoImport._
  import GenerativePlugin.autoImport._
  import TypelevelSitePlugin.autoImport._
  import scalafix.sbt.ScalafixPlugin.autoImport._
  import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._
  import scoverage.ScoverageSbtPlugin.autoImport._
  import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
  import DockerComposePlugin.autoImport._
  import DockerImagePlugin.autoImport._

  private val primaryJavaOSCond = Def.setting {
    val java = githubWorkflowJavaVersions.value.head
    val os = githubWorkflowOSes.value.head
    s"matrix.java == '${java.render}' && matrix.os == '${os}'"
  }

  private val noScala3Cond = Def.setting {
    primaryJavaOSCond.value + s" && matrix.scala != '$Scala3'"
  }

  private val onlyScala3Cond = Def.setting {
    primaryJavaOSCond.value + s" && matrix.scala == '$Scala3'"
  }

  override def buildSettings = Seq(
    tlBaseVersion := "0.0",
    organization := "io.github.etspaceman",
    organizationName := "etspaceman",
    scalaVersion := Scala213,
    crossScalaVersions := Seq(Scala213, Scala212, Scala3),
    githubWorkflowScalaVersions := crossScalaVersions.value,
    startYear := Some(2023),
    licenses := Seq(License.Apache2),
    developers := List(tlGitHubDev("etspaceman", "Eric Meisel")),
    coverageScalacPluginVersion := "2.0.7",
    tlSitePublishBranch := Some("main"),
    tlSiteApiPackage := Some("kinesis4cats"),
    tlSiteRelatedProjects ++= Seq(
      TypelevelProject.CatsEffect,
      TypelevelProject.Fs2,
      TypelevelProject.Http4s,
      "kcl" -> url(
        "https://docs.aws.amazon.com/streams/latest/dev/shared-throughput-kcl-consumers.html"
      ),
      "kpl" -> url(
        "https://docs.aws.amazon.com/streams/latest/dev/developing-producers-with-kpl.html"
      ),
      "aws-java-sdk-v1" -> url(
        "https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/welcome.html"
      ),
      "aws-java-sdk-v2" -> url(
        "https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html"
      ),
      "circe" -> url("https://circe.github.io/circe/"),
      "ciris" -> url("https://cir.is/"),
      "localstack" -> url("https://localstack.cloud/")
    ),
    githubWorkflowBuild := {
      val style = (tlCiHeaderCheck.value, tlCiScalafmtCheck.value) match {
        case (true, true) => // headers + formatting
          List(
            WorkflowStep.Sbt(
              List(
                "headerCheckAll",
                "fmtCheck"
              ),
              name = Some("Check headers and formatting"),
              cond = Some(primaryJavaOSCond.value)
            )
          )
        case (true, false) => // headers
          List(
            WorkflowStep.Sbt(
              List("headerCheckAll"),
              name = Some("Check headers"),
              cond = Some(primaryJavaOSCond.value)
            )
          )
        case (false, true) => // formatting
          List(
            WorkflowStep.Sbt(
              List("fmtCheck"),
              name = Some("Check formatting"),
              cond = Some(primaryJavaOSCond.value)
            )
          )
        case (false, false) => Nil // nada
      }

      val testWithCoverage = List(
        WorkflowStep.Sbt(
          List("IT / dockerComposeUp", "cov", "IT / dockerComposeDown"),
          name = Some("Test with coverage"),
          cond = Some(noScala3Cond.value)
        )
      )

      val test = List(
        WorkflowStep.Sbt(
          List(
            "IT / dockerComposeUp",
            "test",
            "IT / test",
            "IT / dockerComposeDown"
          ),
          name = Some("Test"),
          cond = Some(onlyScala3Cond.value)
        )
      )

      val scalafix =
        if (tlCiScalafixCheck.value)
          List(
            WorkflowStep.Sbt(
              List("fixCheck"),
              name = Some("Check scalafix lints"),
              cond = Some(noScala3Cond.value)
            )
          )
        else Nil

      val mima =
        if (tlCiMimaBinaryIssueCheck.value)
          List(
            WorkflowStep.Sbt(
              List("mimaReportBinaryIssues"),
              name = Some("Check binary compatibility"),
              cond = Some(primaryJavaOSCond.value)
            )
          )
        else Nil

      val doc =
        if (tlCiDocCheck.value)
          List(
            WorkflowStep.Sbt(
              List("doc"),
              name = Some("Generate API documentation"),
              cond = Some(primaryJavaOSCond.value)
            )
          )
        else Nil

      val cov =
        List(
          WorkflowStep.Use(
            UseRef.Public("codecov", "codecov-action", "v2"),
            name = Some("Upload coverage")
          )
        )

      style ++ testWithCoverage ++ test ++ scalafix ++ mima ++ doc ++ cov
    },
    githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17")),
    tlCiScalafixCheck := true
  ) ++ tlReplaceCommandAlias(
    "prePR",
    mkCommand(
      List(
        "reload",
        "project /",
        "clean",
        "githubWorkflowGenerate",
        "cpl",
        "+headerCreateAll",
        "pretty",
        "pretty", // Run pretty twice, as sometimes the formats don't take
        "set ThisBuild / tlFatalWarnings := tlFatalWarningsInCi.value",
        "doc",
        "session clear"
      )
    )
  )

  override def projectSettings = Seq(
    Test / testOptions ++= {
      List(Tests.Argument(MUnitFramework, "+l"))
    },
    ThisBuild / scalafixDependencies += OrganizeImports,
    ThisBuild / semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies ++= Seq(
      Cats.core,
      Cats.effect,
      CatsRetry,
      Munit.core % Test,
      Munit.catsEffect % Test,
      Munit.scalacheck % Test,
      Munit.scalacheckEffect % Test,
      Logback % Test,
      Scalacheck % Test,
      FS2.core % Test,
      FS2.reactiveStreams % Test
    ),
    moduleName := "kinesis4cats-" + name.value,
    headerLicense := Some(
      HeaderLicense.ALv2(s"${startYear.value.get}-2023", organizationName.value)
    ),
    Test / fork := true,
    Compile / doc / sources := {
      if (scalaVersion.value.startsWith("3.")) Nil
      else (Compile / doc / sources).value
    }
  ) ++ Seq(
    addCommandAlias("cpl", ";+Test / compile;+IT / compile"),
    addCommandAlias(
      "fixCheck",
      ";Compile / scalafix --check;Test / scalafix --check;IT / scalafix --check"
    ),
    addCommandAlias(
      "fix",
      ";Compile / scalafix;Test / scalafix;IT / scalafix"
    ),
    addCommandAlias(
      "fmtCheck",
      ";Compile / scalafmtCheck;Test / scalafmtCheck;IT / scalafmtCheck;scalafmtSbtCheck"
    ),
    addCommandAlias(
      "fmt",
      ";Compile / scalafmt;Test / scalafmt;IT / scalafmt;scalafmtSbt"
    ),
    addCommandAlias(
      "pretty",
      "fix;fmt"
    ),
    addCommandAlias(
      "prettyCheck",
      ";fixCheck;fmtCheck"
    ),
    addCommandAlias(
      "cov",
      ";clean;coverage;test;IT/test;coverageReport;coverageOff"
    )
  ).flatten
}

object Kinesis4CatsPluginKeys {
  val Scala212 = "2.12.17"
  val Scala213 = "2.13.10"
  val Scala3 = "3.2.1"

  val MUnitFramework = new TestFramework("munit.Framework")

  import scalafix.sbt.ScalafixPlugin.autoImport._
  import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._

  val IT = config("it").extend(Test)

  final implicit class Kinesi4catsProjectOps(private val p: Project)
      extends AnyVal {
    def enableIntegrationTests = p
      .configs(IT)
      .settings(inConfig(IT) {
        ScalafmtPlugin.scalafmtConfigSettings ++
          scalafixConfigSettings(IT) ++
          BloopSettings.default ++
          Defaults.testSettings ++
          headerSettings(IT) ++
          Seq(
            parallelExecution := false,
            javaOptions += "-Dcom.amazonaws.sdk.disableCertChecking=true"
          )
      })
  }
}
