package dev.jlo.kitsune.search;

import dev.jlo.kitsune.embedding.FeatureVocabulary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FullTextQuery {
  private final String matchExpression;

  private FullTextQuery(String matchExpression) {
    this.matchExpression = matchExpression;
  }

  public static FullTextQuery parse(String raw) {
    List<String> tokens = FeatureVocabulary.tokenize(raw);
    if (tokens.isEmpty()) {
      return new FullTextQuery("");
    }
    List<String> groups = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      boolean prefix = i == tokens.size() - 1;
      StringBuilder group = new StringBuilder("(");
      group.append(quote(token));
      if (prefix) {
        group.append('*');
      }
      List<String> aliases = new ArrayList<>(FeatureVocabulary.aliasesFor(token));
      Collections.sort(aliases);
      for (String alias : aliases) {
        group.append(" OR ").append(quote(alias));
      }
      group.append(')');
      groups.add(group.toString());
    }
    return new FullTextQuery(String.join(" AND ", groups));
  }

  public boolean isEmpty() {
    return matchExpression.isEmpty();
  }

  public String matchExpression() {
    return matchExpression;
  }

  static String quote(String token) {
    return "\"" + token.replace("\"", "\"\"") + "\"";
  }
}
