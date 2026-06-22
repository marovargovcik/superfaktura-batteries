package superfaktura.rule

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Rules:

  private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

  def firstMatch(rules: RuleSet, derivedName: String, iban: Option[String]): Option[Rule] =
    rules.rules.find(rule => matches(rule.when, derivedName, iban))

  def renderName(template: String, date: LocalDate): String =
    template.replace("{date}", dateFormat.format(date))

  private def matches(when: RuleMatch, derivedName: String, iban: Option[String]): Boolean =
    when match
      case RuleMatch.ExactName(name) => derivedName == name
      case RuleMatch.PartialName(fragment) => derivedName.contains(fragment)
      case RuleMatch.ExactRecipient(account) => iban.contains(account)
end Rules
