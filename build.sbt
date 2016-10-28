import com.typesafe.sbt.pgp.PgpKeys
import sbtunidoc.Plugin.UnidocKeys._
import sbtunidoc.Plugin.{ScalaUnidoc, unidocSettings => baseUnidocSettings}

// For getting Scoverage out of the generated POM
import scala.xml.Elem
import scala.xml.transform.{RewriteRule, RuleTransformer}

val catsVersion = "0.8.0"
val scalazVersion = "7.2.6"

lazy val doNotPublishArtifact = Seq(
  publishArtifact := false,
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in (Compile, packageSrc) := false,
  publishArtifact in (Compile, packageBin) := false
)

lazy val warnUnusedImport = Seq(
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 10)) =>
        Seq()
      case Some((2, n)) if n >= 11 =>
        Seq("-Ywarn-unused-import")
    }
  },
  scalacOptions in (Compile, console) ~= {_.filterNot("-Ywarn-unused-import" == _)},
  scalacOptions in (Test, console) <<= (scalacOptions in (Compile, console))
)

lazy val sharedSettings = warnUnusedImport ++ Seq(
  organization := "io.monix",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq("2.10.6", "2.11.8", "2.12.0-RC2"),

  scalacOptions ++= Seq(
    // warnings
    "-unchecked", // able additional warnings where generated code depends on assumptions
    "-deprecation", // emit warning for usages of deprecated APIs
    "-feature", // emit warning usages of features that should be imported explicitly
    // Features enabled by default
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:experimental.macros",
    // possibly deprecated options
    "-Ywarn-dead-code",
    "-Ywarn-inaccessible"
  ),

  // Force building with Java 8
  initialize := {
    val required = "1.8"
    val current  = sys.props("java.specification.version")
    assert(current == required, s"Unsupported build JDK: java.specification.version $current != $required")
  },

  // Targeting Java 6, but only for Scala <= 2.11
  javacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion <= 11 =>
      // generates code with the Java 6 class format
      Seq("-source", "1.6", "-target", "1.6")
    case _ =>
      // For 2.12 we are targeting the Java 8 class format
      Seq.empty
  }),

  // Targeting Java 6, but only for Scala <= 2.11
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion <= 11 =>
      // generates code with the Java 6 class format
      Seq("-target:jvm-1.6")
    case _ =>
      // For 2.12 we are targeting the Java 8 class format
      Seq.empty
  }),

  // version specific compiler options
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion >= 11 =>
      Seq(
        // Turns all warnings into errors ;-)
        "-Xfatal-warnings",
        // Enables linter options
        "-Xlint:adapted-args", // warn if an argument list is modified to match the receiver
        "-Xlint:nullary-unit", // warn when nullary methods return Unit
        "-Xlint:inaccessible", // warn about inaccessible types in method signatures
        "-Xlint:nullary-override", // warn when non-nullary `def f()' overrides nullary `def f'
        "-Xlint:infer-any", // warn when a type argument is inferred to be `Any`
        "-Xlint:missing-interpolator", // a string literal appears to be missing an interpolator id
        "-Xlint:doc-detached", // a ScalaDoc comment appears to be detached from its element
        "-Xlint:private-shadow", // a private field (or class parameter) shadows a superclass field
        "-Xlint:type-parameter-shadow", // a local type parameter shadows a type already in scope
        "-Xlint:poly-implicit-overload", // parameterized overloaded implicit methods are not visible as view bounds
        "-Xlint:option-implicit", // Option.apply used implicit view
        "-Xlint:delayedinit-select", // Selecting member of DelayedInit
        "-Xlint:by-name-right-associative", // By-name parameter of right associative operator
        "-Xlint:package-object-classes", // Class or object defined in package object
        "-Xlint:unsound-match" // Pattern match may not be typesafe
      )
    case _ =>
      Seq.empty
  }),

  // Turning off fatal warnings for ScalaDoc, otherwise we can't release.
  scalacOptions in (Compile, doc) ~= (_ filterNot (_ == "-Xfatal-warnings")),

  // ScalaDoc settings
  autoAPIMappings := true,
  scalacOptions in ThisBuild ++= Seq(
    // Note, this is used by the doc-source-url feature to determine the
    // relative path of a given source file. If it's not a prefix of a the
    // absolute path of the source file, the absolute path of that file
    // will be put into the FILE_SOURCE variable, which is
    // definitely not what we want.
    "-sourcepath", file(".").getAbsolutePath.replaceAll("[.]$", "")
  ),

  parallelExecution in Test := false,
  parallelExecution in IntegrationTest := false,
  testForkedParallel in Test := false,
  testForkedParallel in IntegrationTest := false,
  concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),

  resolvers ++= Seq(
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
    Resolver.sonatypeRepo("releases")
  ),

  // https://github.com/sbt/sbt/issues/2654
  incOptions := incOptions.value.withLogRecompileOnMacro(false),

  // -- Settings meant for deployment on oss.sonatype.org

  useGpg := true,
  useGpgAgent := true,
  usePgpKeyHex("2673B174C4071B0E"),

  publishMavenStyle := true,
  releaseCrossBuild := true,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,

  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },

  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false }, // removes optional dependencies

  // For evicting Scoverage out of the generated POM
  // See: https://github.com/scoverage/sbt-scoverage/issues/153
  pomPostProcess := { (node: xml.Node) =>
    new RuleTransformer(new RewriteRule {
      override def transform(node: xml.Node): Seq[xml.Node] = node match {
        case e: Elem
            if e.label == "dependency" && e.child.exists(child => child.label == "groupId" && child.text == "org.scoverage") => Nil
        case _ => Seq(node)
      }
    }).transform(node).head
  },

  pomExtra :=
    <url>https://monix.io/</url>
      <licenses>
        <license>
          <name>Apache License, Version 2.0</name>
          <url>https://www.apache.org/licenses/LICENSE-2.0</url>
          <distribution>repo</distribution>
        </license>
      </licenses>
      <scm>
        <url>git@github.com:monix/monix.git</url>
        <connection>scm:git:git@github.com:monix/monix.git</connection>
      </scm>
      <developers>
        <developer>
          <id>alex_ndc</id>
          <name>Alexandru Nedelcu</name>
          <url>https://alexn.org</url>
        </developer>
      </developers>
)

lazy val crossSettings = sharedSettings ++ Seq(
  unmanagedSourceDirectories in Compile <+= baseDirectory(_.getParentFile / "shared" / "src" / "main" / "scala"),
  unmanagedSourceDirectories in Test <+= baseDirectory(_.getParentFile / "shared" / "src" / "test" / "scala")
)

def scalaPartV = Def setting (CrossVersion partialVersion scalaVersion.value)
lazy val crossVersionSharedSources: Seq[Setting[_]] =
  Seq(Compile, Test).map { sc =>
    (unmanagedSourceDirectories in sc) ++= {
      (unmanagedSourceDirectories in sc).value.map { dir =>
        scalaPartV.value match {
          case Some((2, y)) if y == 10 => new File(dir.getPath + "_2.10")
          case Some((2, y)) if y == 11 => new File(dir.getPath + "_2.11")
          case Some((2, y)) if y == 12 => new File(dir.getPath + "_2.12")
        }
      }
    }
  }

lazy val requiredMacroCompatDeps = Seq(
  libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion >= 11 =>
      Seq(
        "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
        "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
        "org.typelevel" %%% "macro-compat" % "1.1.1" % "provided",
        compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
      )
    case _ =>
      Seq(
        "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
        "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
        "org.typelevel" %%% "macro-compat" % "1.1.1",
        compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
      )
  }))

lazy val unidocSettings = baseUnidocSettings ++ Seq(
  autoAPIMappings := true,
  unidocProjectFilter in (ScalaUnidoc, unidoc) :=
    inProjects(typesJVM, executionJVM, evalJVM, reactiveJVM, catsJVM, scalaz72JVM),

  // Never include Java classes in ScalaDoc
  sources in (ScalaUnidoc, unidoc) ~= (_ filter (_.getName endsWith ".scala")),

  scalacOptions in (ScalaUnidoc, unidoc) +=
    "-Xfatal-warnings",
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Opts.doc.title(s"Monix"),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Opts.doc.sourceUrl(s"https://github.com/monix/monix/tree/v${version.value}€{FILE_PATH}.scala"),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Seq("-doc-root-content", file("rootdoc.txt").getAbsolutePath),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Opts.doc.version(s"${version.value}")
)

lazy val testSettings = Seq(
  testFrameworks := Seq(new TestFramework("minitest.runner.Framework")),
  libraryDependencies += "io.monix" %%% "minitest-laws" % "0.26" % "test"
)

lazy val scalaJSSettings = Seq(
  coverageExcludedFiles := ".*"
)

lazy val cmdlineProfile =
  sys.props.getOrElse("sbt.profile", default = "")

def profile: Project ⇒ Project = pr => cmdlineProfile match {
  case "coverage" => pr
  case _ => pr.disablePlugins(scoverage.ScoverageSbtPlugin)
}

lazy val monix = project.in(file("."))
  .configure(profile)
  .aggregate(coreJVM, coreJS, tckTests)
  .settings(sharedSettings)
  .settings(doNotPublishArtifact)
  .settings(unidocSettings)

lazy val coreJVM = project.in(file("monix/jvm"))
  .configure(profile)
  .dependsOn(typesJVM, executionJVM, evalJVM, reactiveJVM)
  .aggregate(typesJVM, executionJVM, evalJVM, reactiveJVM, catsJVM, scalaz72JVM)
  .settings(crossSettings)
  .settings(name := "monix")

lazy val coreJS = project.in(file("monix/js"))
  .configure(profile)
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(typesJS, executionJS, evalJS, reactiveJS)
  .aggregate(typesJS, executionJS, evalJS, reactiveJS, catsJS, scalaz72JS)
  .settings(crossSettings)
  .settings(scalaJSSettings)
  .settings(name := "monix")

lazy val typesCommon = crossSettings ++ testSettings ++
  requiredMacroCompatDeps ++ Seq(
    name := "monix-types",
    // Suppress macro warnings in our own tests
    scalacOptions in (Test, console) ~= (_ filterNot (_ == "-Xfatal-warnings"))
  )

lazy val typesJVM = project.in(file("monix-types/jvm"))
  .configure(profile)
  .settings(typesCommon)

lazy val typesJS = project.in(file("monix-types/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .settings(typesCommon)
  .settings(scalaJSSettings)

lazy val executionCommon = crossVersionSharedSources ++ Seq(
  name := "monix-execution"
)

lazy val executionJVM = project.in(file("monix-execution/jvm"))
  .configure(profile)
  .settings(crossSettings)
  .settings(testSettings)
  .settings(requiredMacroCompatDeps)
  .settings(executionCommon)
  .settings(libraryDependencies += "org.reactivestreams" % "reactive-streams" % "1.0.0")

lazy val executionJS = project.in(file("monix-execution/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .settings(crossSettings)
  .settings(scalaJSSettings)
  .settings(testSettings)
  .settings(requiredMacroCompatDeps)
  .settings(executionCommon)

lazy val evalCommon =
  crossSettings ++ testSettings ++ Seq(
    name := "monix-eval"
  )

lazy val evalJVM = project.in(file("monix-eval/jvm"))
  .configure(profile)
  .dependsOn(typesJVM % "compile->compile; test->test")
  .dependsOn(executionJVM)
  .settings(evalCommon)

lazy val evalJS = project.in(file("monix-eval/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .dependsOn(typesJS % "compile->compile; test->test")
  .dependsOn(executionJS)
  .settings(scalaJSSettings)
  .settings(evalCommon)

lazy val reactiveCommon =
  crossSettings ++ testSettings ++ Seq(
    name := "monix-reactive"
  )

lazy val reactiveJVM = project.in(file("monix-reactive/jvm"))
  .configure(profile)
  .dependsOn(typesJVM % "compile->compile; test->test")
  .dependsOn(executionJVM, evalJVM)
  .settings(reactiveCommon)

lazy val reactiveJS = project.in(file("monix-reactive/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .dependsOn(typesJS % "compile->compile; test->test")
  .dependsOn(executionJS, evalJS)
  .settings(reactiveCommon)
  .settings(scalaJSSettings)

lazy val catsCommon =
  crossSettings ++ testSettings ++ Seq(
    name := "monix-cats",
    testFrameworks := Seq(new TestFramework("minitest.runner.Framework")),
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-laws" % catsVersion % "test"
    ))

lazy val catsJVM = project.in(file("monix-cats/jvm"))
  .configure(profile)
  .dependsOn(typesJVM)
  .dependsOn(reactiveJVM % "test")
  .settings(catsCommon)

lazy val catsJS = project.in(file("monix-cats/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .dependsOn(typesJS)
  .dependsOn(reactiveJS % "test")
  .settings(catsCommon)
  .settings(scalaJSSettings)

lazy val scalaz72Common =
  crossSettings ++ testSettings ++ Seq(
    name := "monix-scalaz-72",
    libraryDependencies ++= Seq(
      "org.scalaz" %%% "scalaz-core" % scalazVersion,
      "org.scalaz" %%% "scalaz-scalacheck-binding" % scalazVersion % "test"
    ))

lazy val scalaz72JVM = project.in(file("monix-scalaz/series-7.2/jvm"))
  .configure(profile)
  .dependsOn(typesJVM)
  .dependsOn(reactiveJVM % "test")
  .settings(scalaz72Common)

lazy val scalaz72JS = project.in(file("monix-scalaz/series-7.2/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .dependsOn(typesJS)
  .dependsOn(reactiveJS % "test")
  .settings(scalaz72Common)
  .settings(scalaJSSettings)

lazy val tckTests = project.in(file("tckTests"))
  .configure(profile)
  .dependsOn(coreJVM)
  .settings(sharedSettings)
  .settings(doNotPublishArtifact)
  .settings(
    libraryDependencies ++= Seq(
      "org.reactivestreams" % "reactive-streams-tck" % "1.0.0" % "test",
      "org.scalatest" %% "scalatest" % "3.0.0" % "test"
    ))

lazy val benchmarks = project.in(file("benchmarks"))
  .configure(profile)
  .dependsOn(coreJVM)
  .enablePlugins(JmhPlugin)
  .settings(sharedSettings)
  .settings(doNotPublishArtifact)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalaz" %% "scalaz-concurrent" % scalazVersion,
      "io.reactivex" %% "rxscala" % "0.26.0"
    ))
