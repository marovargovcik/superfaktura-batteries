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
        FileRuleStore.at[IO](path).load.unsafeRunSync().rules shouldBe
          List(Rule(RuleMatch.ExactName("SHELL 8203"), Some("Fuel"), None))
    }

    "rejects a rule that neither renames nor attaches" in {
      val content = """{ "rules": [ { "when": { "type": "PartialName", "fragment": "AWS" } } ] }"""
      withFile(content): path =>
        intercept[CliError.RulesInvalid](FileRuleStore.at[IO](path).load.unsafeRunSync())
        ()
    }

    "fails with FileAccess when the file is missing" in {
      intercept[CliError.FileAccess](FileRuleStore.at[IO](Paths.get("does-not-exist.json")).load.unsafeRunSync())
      ()
    }
  }
end FileRuleStoreTest
