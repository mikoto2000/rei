package dev.mikoto2000.rei.toolcontract;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

/** A validation problem at an argument path, without the rejected input value. */
public record ArgumentIssue(String path, String requirement) implements Serializable {
  public static String describe(List<ArgumentIssue> issues, String separator) {
    return issues.stream()
        .map(issue -> issue.path() + ": " + issue.requirement())
        .collect(Collectors.joining(separator));
  }
}
