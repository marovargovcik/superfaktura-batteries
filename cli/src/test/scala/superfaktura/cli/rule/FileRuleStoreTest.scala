package superfaktura.cli.rule

import java.nio.file.{Files as JFiles, Path, Paths}

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import superfaktura.CliError
import superfaktura.rule.{Rule, RuleMatch}

class FileRuleStoreTest extends AnyFreeSpec with Matchers:

  private def withFile(content: String)(test: Path => Any): Unit =
    val path = JFiles.createTempFile("rules", ".json")
    try
      JFiles.writeString(path, content)
      test(path)
    finally JFiles.delete(path)
    ()

  "FileRuleStore.load" - {
    "decodes a valid rules file" in {
      val content =
        """{ "rules": [ { "when": { "type": "ExactName", "name": "SHELL 8203" }, "rename": "Fuel" } ] }"""
      withFile(content): path =>
        val test =
          for
            ruleSet <- FileRuleStore.at[IO](path).load
          yield ruleSet.rules shouldBe List(Rule(RuleMatch.ExactName("SHELL 8203"), Some("Fuel"), None))
        test.unsafeRunSync()
    }

    "rejects a rule that neither renames nor attaches" in {
      val content = """{ "rules": [ { "when": { "type": "PartialName", "fragment": "AWS" } } ] }"""
      withFile(content): path =>
        val test =
          for
            result <- FileRuleStore.at[IO](path).load.attempt
          yield result match
            case Left(_: CliError.RulesInvalid) => succeed
            case other => fail(s"expected RulesInvalid, got: $other")
        test.unsafeRunSync()
    }

    "fails with FileAccess when the file is missing" in {
      val test =
        for
          result <- FileRuleStore.at[IO](Paths.get("does-not-exist.json")).load.attempt
        yield result match
          case Left(_: CliError.FileAccess) => succeed
          case other => fail(s"expected FileAccess, got: $other")
      test.unsafeRunSync()
    }
  }
end FileRuleStoreTest
