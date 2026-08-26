package dev.jlo.kitsune.search;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FullTextQueryTest {
  @Test
  void emptyAndPunctuationAreEmpty() {
    assertTrue(FullTextQuery.parse("").isEmpty());
    assertTrue(FullTextQuery.parse("   ").isEmpty());
    assertTrue(FullTextQuery.parse("!!!").isEmpty());
    assertEquals("", FullTextQuery.parse("!!!").matchExpression());
  }

  @Test
  void quotesOperatorsAndWildcardsBecomeLiteralsOrAreDropped() {
    FullTextQuery query = FullTextQuery.parse("oak AND planks OR \"foo\"* (bar)");
    assertFalse(query.isEmpty());
    String match = query.matchExpression();
    assertFalse(match.contains(" AND AND "));
    assertTrue(match.contains("(\"oak\")"));
    assertTrue(match.contains("AND"));
    assertTrue(match.startsWith("("));
  }

  @Test
  void lastTokenIsPrefixAndAliasesStayInsideTheTokenGroup() {
    FullTextQuery query = FullTextQuery.parse("oak log");
    assertEquals("(\"oak\") AND (\"log\"* OR \"logs\")", query.matchExpression());
  }

  @Test
  void foodExpandsAliasInsideTheOnlyGroup() {
    assertEquals("(\"food\"* OR \"edible\")", FullTextQuery.parse("food").matchExpression());
  }

  @Test
  void plankQueryExpandsPlanksAliasInsideTheOnlyGroup() {
    assertEquals("(\"plank\"* OR \"planks\")", FullTextQuery.parse("plank").matchExpression());
  }

  @Test
  void parseIsDeterministic() {
    assertEquals(
        FullTextQuery.parse("Oak Plank").matchExpression(),
        FullTextQuery.parse("oak plank").matchExpression());
  }
}
