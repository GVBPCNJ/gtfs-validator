/*
 * Copyright 2020 MobilityData IO
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

package org.mobilitydata.gtfsvalidator.outputcomparator.io;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Type;

/**
 * Used to deserialize a validation report. This represents a validation report as a list of {@code
 * NoticeAggregate} which provides information about each notice generated during a GTFS dataset
 * validation.
 */
public class ValidationReportDeserializer implements JsonDeserializer<ValidationReport> {
  private static final Gson GSON =
      new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().create();
  private static final String NOTICES_MEMBER_NAME = "notices";

  /**
   * Return the sorted set of error codes from a list of {@code NoticeAggregate}.
   *
   * @return the sorted set of error codes from a list of {@code NoticeAggregate}.
   */
  private static ImmutableSet<String> extractErrorCodes(ImmutableList<NoticeSummary> notices) {
    ImmutableSet.Builder<String> errorCodesSetBuilder = new ImmutableSet.Builder<>();
    for (NoticeSummary noticeSummary : notices) {
      if (noticeSummary.isError()) {
        errorCodesSetBuilder.add(noticeSummary.getCode());
      }
    }
    return errorCodesSetBuilder.build();
  }

  @Override
  public ValidationReport deserialize(
      JsonElement json, Type typoOfT, JsonDeserializationContext context) {
    ImmutableList.Builder<NoticeSummary> noticeListBuilder = new ImmutableList.Builder<>();
    JsonObject rootObject = json.getAsJsonObject();
    JsonArray noticesArray = rootObject.getAsJsonArray(NOTICES_MEMBER_NAME);
    noticesArray.forEach(
        childObject -> noticeListBuilder.add(GSON.fromJson(childObject, NoticeSummary.class)));
    ImmutableList<NoticeSummary> notices = noticeListBuilder.build();

    return new ValidationReport(notices, extractErrorCodes(notices));
  }
}
