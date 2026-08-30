package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.ArtifactRecord;
import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The read side that answers the golden question: filter a repository's records by exact-match
 * metadata predicates, newest-first, with an optional {@code latest} collapse to the newest per
 * ({@code git.branch.name}, {@code qits.userflow.name}) group. Nothing fancier (no ranges, no
 * full-text) in iteration one.
 *
 * <p>Predicate filtering is done in memory over the repository's records — correct and simple at
 * the current "keep everything, modest per-repo volume" scale; an indexed metadata-join query is
 * the backlog trigger if a single repository's record count ever makes this loading cost real.
 */
@ApplicationScoped
public class ArtifactQueryService {

  @Inject ArtifactRepositoryService repositoryService;

  @Inject ArtifactRecordRepository records;

  public List<ArtifactRecord> query(
      String repoName, Map<String, String> predicates, boolean latest) {
    return query(repoName, predicates, latest, ArtifactListFilter.NONE);
  }

  public List<ArtifactRecord> query(
      String repoName,
      Map<String, String> predicates,
      boolean latest,
      ArtifactListFilter filter) {
    repositoryService.require(repoName);

    List<ArtifactRecord> matching = new ArrayList<>();
    for (ArtifactRecord record : records.listByRepositoryNewestFirst(repoName)) {
      if (matchesAll(record, predicates)
          && filter.matches(record.size, record.createdAt, record.accessedAt)) {
        matching.add(record);
      }
    }
    return latest ? collapseToLatest(matching) : matching;
  }

  private static boolean matchesAll(ArtifactRecord record, Map<String, String> predicates) {
    for (Map.Entry<String, String> predicate : predicates.entrySet()) {
      if (!predicate.getValue().equals(record.metadata.get(predicate.getKey()))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Keeps the newest record per ({@code git.branch.name}, {@code qits.userflow.name}) group. The
   * input is already newest-first, so the first record seen for a group wins; groups appear in
   * first-seen (newest-overall) order.
   */
  private static List<ArtifactRecord> collapseToLatest(List<ArtifactRecord> newestFirst) {
    Set<List<String>> seen = new LinkedHashSet<>();
    List<ArtifactRecord> latest = new ArrayList<>();
    for (ArtifactRecord record : newestFirst) {
      List<String> key =
          List.of(
              nullToEmpty(record.metadata.get(MetadataKeys.GIT_BRANCH_NAME)),
              nullToEmpty(record.metadata.get(MetadataKeys.USERFLOW_NAME)));
      if (seen.add(key)) {
        latest.add(record);
      }
    }
    return latest;
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
