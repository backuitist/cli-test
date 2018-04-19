package org.backuity.clist.parse

import org.backuity.clist._
import org.junit.Test
import java.lang.System.{lineSeparator => crlf}

class OptionTest extends ClistTestBase {

  import OptionTest._

  @Test
  def doNotReuseTheSameOptionMoreThanOnce(): Unit = {
    Cli.parse(Array("-o", "--opt1")).throwExceptionOnError().withCommand(new Run)() must throwA[ParsingException].withMessage(
      "No option found for --opt1")
  }

  @Test
  def incorrectCommandOption(): Unit = {
    Cli.parse(Array("--unknown-option", "target")).withCommand(new Run)() must exitWithCode(1)
    console.content must_== (Usage.Default.show("x",Commands(new Run)) + crlf +
      "No option found for --unknown-option" + crlf)
  }

  @Test
  def useDefaultValueForMissingEnvVar(): Unit = {
    Cli.parse(Array()).withCommand(EnvRun)() must_== Some(EnvRun)
    EnvRun.missingEnvOpt must_== "defaultValue"
    EnvRun.exportedEnvOpt must_== "fooBarBaz"
  }

  @Test
  def useExplicitValueForMissingEnvVar(): Unit = {
    Cli.parse(Array("--missing-env-opt=explicitValue")).withCommand(EnvRun)() must_== Some(EnvRun)
    EnvRun.missingEnvOpt must_== "explicitValue"
    EnvRun.exportedEnvOpt must_== "fooBarBaz"
  }

  @Test
  def overrideExportedEnvVarWithExplicitValue(): Unit = {
    Cli.parse(Array("--exported-env-opt=explicitValue", "--missing-env-opt=explicitValue")).withCommand(EnvRun)() must_== Some(EnvRun)
    EnvRun.missingEnvOpt must_== "explicitValue"
    EnvRun.exportedEnvOpt must_== "explicitValue"
  }

  @Test
  def useIntValueForEnvVar(): Unit = {
    Cli.parse(Array()).withCommand(EnvRun)() must_== Some(EnvRun)
    EnvRun.missingEnvOpt must_== "defaultValue"
    EnvRun.exportedEnvOpt must_== "fooBarBaz"
    EnvRun.intEnvOpt must_== 4
  }

}

object OptionTest {

  class Run extends Command {
    var opt1 = opt[Boolean](abbrev = "o")
  }

  object EnvRun extends Command {
    var missingEnvOpt = opt[String](useEnv = true, default = "defaultValue")
    var exportedEnvOpt = opt[String](useEnv = true, default = "shouldBeNeverUsed")
    var intEnvOpt = opt[Int](useEnv = true, default = 1)
  }

}
