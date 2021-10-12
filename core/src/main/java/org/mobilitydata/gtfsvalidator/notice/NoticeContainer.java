/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mobilitydata.gtfsvalidator.notice;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Container for validation notices (errors and warnings).
 *
 * <p>This class is not intentionally not thread-safe to increase performance. Each thread has it's
 * own NoticeContainer, and after execution is complete the results are merged.
 */
public class NoticeContainer {
  /** Limit on the amount notices of the same type and severity. */
  private static final int MAX_PER_NOTICE_TYPE_AND_SEVERITY = 100_000;

  /**
   * Limit on the total amount of stored validation notices.
   *
   * <p>This is a measure to prevent OOM in the rare case when each row in a large file (such as
   * stop_times.txt or shapes.txt) produces a notice. Since this case is rare, we just introduce a
   * total limit on the amount of notices instead of counting amount of notices of each type.
   *
   * <p>Note that system errors are not limited since we don't expect to have a lot of them.
   */
  private static final int MAX_VALIDATION_NOTICES = 10_000_000;

  /** Limit on the amount of exported notices */
  private static final int MAX_EXPORTS_PER_NOTICE_TYPE = 1_000;

  private final int maxTotalValidationNotices;
  private final int maxValidationNoticePerType;
  private final int maxExportPerNoticeType;
  private final List<ValidationNotice> validationNotices = new ArrayList<>();
  private final List<SystemError> systemErrors = new ArrayList<>();
  private final Map<String, Integer> noticesCountPerTypeAndSeverity = new HashMap<>();
  private boolean hasValidationErrors = false;

  /**
   * Used to specify limits on amount of notices in this {@code NoticeContainer}.
   *
   * @param maxTotalValidationNotices limit on the total amount of {@code Notice}s in this {@code
   *     NoticeContainer}
   * @param maxPerNoticeTypeAndSeverity limit on the amount of {@code Notice}s of same type and
   *     severity in this {@code NoticeContainer}
   * @param maxExportPerNoticeType limit on the amount of {@code Notice}s exported from this {@code
   *     NoticeContainer}
   */
  public NoticeContainer(
      int maxTotalValidationNotices, int maxPerNoticeTypeAndSeverity, int maxExportPerNoticeType) {
    this.maxTotalValidationNotices = maxTotalValidationNotices;
    this.maxValidationNoticePerType = maxPerNoticeTypeAndSeverity;
    this.maxExportPerNoticeType = maxExportPerNoticeType;
  }

  /** Used if no constant is provided: limits on amount of notices are set using class constants. */
  public NoticeContainer() {
    this.maxTotalValidationNotices = MAX_VALIDATION_NOTICES;
    this.maxValidationNoticePerType = MAX_PER_NOTICE_TYPE_AND_SEVERITY;
    this.maxExportPerNoticeType = MAX_EXPORTS_PER_NOTICE_TYPE;
  }

  /** Adds a new validation notice to the container (if there is capacity). */
  public void addValidationNotice(ValidationNotice notice) {
    if (notice.isError()) {
      hasValidationErrors = true;
    }
    if (validationNotices.size() >= maxTotalValidationNotices) {
      return;
    }
    updateNoticeCount(notice);
    if (noticesCountPerTypeAndSeverity.get(notice.getMappingKey()) <= maxValidationNoticePerType) {
      validationNotices.add(notice);
    }
  }

  /** Adds a new system error to the container. */
  public void addSystemError(SystemError error) {
    updateNoticeCount(error);
    systemErrors.add(error);
  }

  /**
   * Updates the count of notices per type and severity.
   *
   * @param notice the notice whose count should be updated
   */
  private void updateNoticeCount(Notice notice) {
    int count = noticesCountPerTypeAndSeverity.getOrDefault(notice.getMappingKey(), 0);
    noticesCountPerTypeAndSeverity.put(notice.getMappingKey(), count + 1);
  }

  /**
   * Adds all validation notices and system errors from another container.
   *
   * <p>This is useful for multithreaded validation: each thread has its own notice container which
   * is merged into the global container when the thread finishes.
   *
   * @param otherContainer a container to take the notices from
   */
  public void addAll(NoticeContainer otherContainer) {
    validationNotices.addAll(otherContainer.validationNotices);
    systemErrors.addAll(otherContainer.systemErrors);
    hasValidationErrors |= otherContainer.hasValidationErrors;
  }

  /** Tells if this container has any {@code ValidationNotice} that is an error. */
  public boolean hasValidationErrors() {
    return hasValidationErrors;
  }

  /** Returns a list of all validation notices in the container. */
  public List<ValidationNotice> getValidationNotices() {
    return Collections.unmodifiableList(validationNotices);
  }

  /** Returns a list of all system errors in the container. */
  public List<SystemError> getSystemErrors() {
    return Collections.unmodifiableList(systemErrors);
  }

  /** Exports all validation notices as JSON. */
  public JsonObject exportValidationNotices() {
    return exportJson(validationNotices);
  }

  /** Exports all system errors as JSON. */
  public JsonObject exportSystemErrors() {
    return exportJson(systemErrors);
  }

  /**
   * Exports notices as JSON.
   *
   * <p>Up to {@link #maxExportPerNoticeType} is exported per each type+severity.
   */
  private <T extends Notice> JsonObject exportJson(List<T> notices) {
    JsonObject root = new JsonObject();
    JsonArray jsonNotices = new JsonArray();
    root.add("notices", jsonNotices);

    for (Collection<T> noticesOfType : groupNoticesByTypeAndSeverity(notices).asMap().values()) {
      JsonObject noticesOfTypeJson = new JsonObject();
      jsonNotices.add(noticesOfTypeJson);
      T firstNotice = noticesOfType.iterator().next();
      noticesOfTypeJson.addProperty("code", firstNotice.getCode());
      noticesOfTypeJson.addProperty("severity", firstNotice.getSeverityLevel().toString());
      noticesOfTypeJson.addProperty(
          "totalNotices", noticesCountPerTypeAndSeverity.get(firstNotice.getMappingKey()));
      JsonArray noticesArrayJson = new JsonArray();
      noticesOfTypeJson.add("notices", noticesArrayJson);
      int i = 0;
      for (T notice : noticesOfType) {
        ++i;
        if (i > maxExportPerNoticeType) {
          // Do not export too many notices for this type.
          break;
        }
        noticesArrayJson.add(notice.getContext());
      }
    }
    return root;
  }

  @VisibleForTesting
  static <T extends Notice> ListMultimap<String, T> groupNoticesByTypeAndSeverity(List<T> notices) {
    ListMultimap<String, T> noticesByType = MultimapBuilder.treeKeys().arrayListValues().build();
    for (T notice : notices) {
      noticesByType.put(notice.getMappingKey(), notice);
    }
    return noticesByType;
  }
}
