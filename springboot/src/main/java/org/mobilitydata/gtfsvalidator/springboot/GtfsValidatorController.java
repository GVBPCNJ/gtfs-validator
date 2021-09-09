/*
 * Copyright 2021 MobilityData IO
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

package org.mobilitydata.gtfsvalidator.springboot;

import static org.mobilitydata.gtfsvalidator.cli.Main.createGtfsInput;
import static org.mobilitydata.gtfsvalidator.cli.Main.exportReport;
import static org.mobilitydata.gtfsvalidator.cli.Main.printSummary;

import com.beust.jcommander.JCommander;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageClass;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import org.mobilitydata.gtfsvalidator.cli.Arguments;
import org.mobilitydata.gtfsvalidator.cli.CliParametersAnalyzer;
import org.mobilitydata.gtfsvalidator.cli.Main;
import org.mobilitydata.gtfsvalidator.input.CountryCode;
import org.mobilitydata.gtfsvalidator.input.CurrentDateTime;
import org.mobilitydata.gtfsvalidator.input.GtfsInput;
import org.mobilitydata.gtfsvalidator.notice.NoticeContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsFeedContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsFeedLoader;
import org.mobilitydata.gtfsvalidator.validator.ValidationContext;
import org.mobilitydata.gtfsvalidator.validator.ValidatorLoader;
import org.mobilitydata.gtfsvalidator.validator.ValidatorLoaderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the validation through a {@code SpringBoot} interface triggered by incoming HTTP requests.
 * Provides methods to push the validation report to Google Cloud Storage.
 */
@RestController
public class GtfsValidatorController {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  static final String VALIDATION_REPORT_BUCKET_NAME_ENV_VAR = "VALIDATION_REPORT_BUCKET";
  private static final String DEFAULT_OUTPUT_BASE = "output";
  private static final String DEFAULT_NUM_THREADS = "8";
  private static final String DEFAULT_BUCKET_LOCATION = "US";
  private static final String MESSAGE_JSON_KEY = "message";
  private static final String STATUS_JSON_KEY = "status";
  private static final String DEFAULT_COUNTRY_CODE = "ZZ";
  private static final String TYPE = "TYPE";
  private static final String PROJECT_ID = "PROJECT_ID";
  private static final String PRIVATE_KEY_ID = "PRIVATE_KEY_ID";
  private static final String PRIVATE_KEY = "PRIVATE_KEY";
  private static final String CLIENT_EMAIL = "CLIENT_EMAIL";
  private static final String CLIENT_ID = "CLIENT_ID";
  private static final String AUTH_URI = "AUTH_URI";
  private static final String TOKEN_URI = "TOKEN_URI";
  private static final String AUTH_PROVIDER_X509_CERT_URL = "AUTH_PROVIDER_X509_CERT_URL";
  private static final String CLIENT_X509_CERT_URL = "CLIENT_X509_CERT_URL";

  /**
   * Validates a dataset based on the query parameters. Returns the the {@code ResponseEntity} that
   * contains information about the response's {@code HttpStatus} and a message (as a {@code
   * String}) that gives more information about success or failure. Please note that this method
   * requires authentication to be setup prior to execution i.e. the following environment variables
   * have to be defined:
   *
   * <ul>
   *   <li>TYPE
   *   <li>PROJECT_ID
   *   <li>PRIVATE_KEY_ID
   *   <li>PRIVATE_KEY
   *   <li>CLIENT_EMAIL
   *   <li>CLIENT_ID
   *   <li>AUTH_URI
   *   <li>TOKEN_URI
   *   <li>AUTH_PROVIDER_X509_CERT_URL
   *   <li>CLIENT_X509_CERT_URL
   *   <li>VALIDATION_REPORT_BUCKET
   * </ul>
   *
   * <p>Please check:
   *
   * <ul>
   *   <li><a href=https://cloud.google.com/storage/docs/naming-buckets>this documentation for
   *       bucket naming guidelines</a>
   *   <li><a
   *       href=https://cloud.google.com/docs/authentication/getting-started#setting_the_environment_variable>this
   *       documentation for authentication procedure</a>
   * </ul>
   *
   * <a href=https://cloud.google.com/storage/docs/json_api/v1/status-codes>Possible HTTP status
   * codes</a>
   *
   * @param outputBase Base directory to store the outputs
   * @param threads Number of threads to use
   * @param countryCode Country code of the feed, e.g., `nl`. It must be a two-letter country code
   *     (ISO 3166-1 alpha-2)")
   * @param url The fully qualified URL to download GTFS archive
   * @param validationReportName The name of the validation report including .json extension.
   * @param datasetId the id of the dataset validated. It is arbitrarily determined by calling code,
   *     it is used to sort and store validation reports in Google Cloud Storage
   * @param commitSha the commit SHA attributed by Github. It is used to sort and store validation
   *     reports in Google Cloud Storage.
   * @return the {@code ResponseEntity} that contains information about the response's {@code
   *     HttpStatus} and a message (as a {@code String}) that gives more information about success
   *     or failure. <a href=https://cloud.google.com/storage/docs/json_api/v1/status-codes>Possible
   *     HTTP status codes</a>
   */
  @GetMapping(value = "/", produces = "application/json; charset=UTF-8")
  @ResponseBody
  public ResponseEntity<HashMap<String, String>> run(
      @RequestParam(required = false, defaultValue = DEFAULT_OUTPUT_BASE) String outputBase,
      @RequestParam(required = false, defaultValue = DEFAULT_NUM_THREADS) String threads,
      @RequestParam(required = false, defaultValue = DEFAULT_COUNTRY_CODE) String countryCode,
      @RequestParam() String url,
      @RequestParam(required = false, defaultValue = Arguments.VALIDATION_REPORT_NAME_JSON)
          String validationReportName,
      @RequestParam(required = false, defaultValue = Arguments.SYSTEM_ERRORS_REPORT_NAME_JSON)
          String systemErrorReportName,
      @RequestParam(required = false, defaultValue = "dataset id value") String datasetId,
      @RequestParam(required = false, defaultValue = "commit sha value") String commitSha) {

    Arguments args =
        queryParametersToArguments(
            outputBase, threads, countryCode, url, validationReportName, systemErrorReportName);
    StringBuilder messageBuilder = new StringBuilder();
    HttpStatus status;
    final long startNanos = System.nanoTime();

    // this should not happen
    if (!CliParametersAnalyzer.isValid(args)) {
      status = HttpStatus.BAD_REQUEST;
      messageBuilder.append(
          "Bad request. Please check query parameters and execution logs for more information.\n");
      return generateResponse(messageBuilder, status);
    }
    ValidatorLoader validatorLoader;
    GtfsInput gtfsInput;
    GtfsFeedContainer feedContainer;
    NoticeContainer noticeContainer = new NoticeContainer();
    try {
      validatorLoader = new ValidatorLoader();
    } catch (ValidatorLoaderException e) {
      return generateResponse(
          messageBuilder.append(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }
    GtfsFeedLoader feedLoader = new GtfsFeedLoader();
    try {
      gtfsInput = createGtfsInput(args);
    } catch (IOException | URISyntaxException ioException) {
      logger.atSevere().withCause(ioException);
      return generateResponseAndExportReport(
          messageBuilder.append(ioException.getMessage()),
          HttpStatus.BAD_REQUEST,
          noticeContainer,
          args);
    }
    ValidationContext validationContext =
        ValidationContext.builder()
            .setCountryCode(
                CountryCode.forStringOrUnknown(
                    args.getCountryCode() == null ? CountryCode.ZZ : args.getCountryCode()))
            .setCurrentDateTime(new CurrentDateTime(ZonedDateTime.now(ZoneId.systemDefault())))
            .build();

    try {
      feedContainer =
          Main.loadAndValidate(
              validatorLoader, feedLoader, noticeContainer, gtfsInput, validationContext);
    } catch (InterruptedException e) {
      logger.atSevere().withCause(e).log("Validation was interrupted");
      messageBuilder.append("Internal error. Please execution logs for more information.\n");
      return generateResponse(
          messageBuilder.append(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }
    Main.closeGtfsInput(gtfsInput, noticeContainer);
    printSummary(startNanos, feedContainer);
    messageBuilder.append("Execution of the validator was successful.\n");
    exportReport(noticeContainer, args);
    return generateResponse(
        messageBuilder,
        pushValidationReportToCloudStorage(commitSha, datasetId, args, messageBuilder));
  }

  /**
   * Converts query parameters to {@code Argument}.
   *
   * @param outputBase Base directory to store the outputs
   * @param threads Number of threads to use
   * @param countryCode Country code of the feed, e.g., `nl`. It must be a two-letter country code
   *     (ISO 3166-1 alpha-2)").
   * @param url The fully qualified URL to download GTFS archive
   * @param validationReportName The name of the validation report including .json extension.
   * @param systemErrorReportName The name of the system error report including .json extension.
   * @return the {@code Argument} instance generated from the query parameters passed.
   */
  private Arguments queryParametersToArguments(
      String outputBase,
      String threads,
      String countryCode,
      String url,
      String validationReportName,
      String systemErrorReportName) {
    final String[] argv = {
      "-o", outputBase,
      "-t", threads,
      "-c", countryCode,
      "-u", url,
      "-v", validationReportName,
      "-e", systemErrorReportName
    };
    Arguments args = new Arguments();
    JCommander jCommander = new JCommander(args);
    jCommander.parse(argv);
    return args;
  }

  private GoogleCredentials generateCredentials(String... keys) throws IOException {
    HashMap<String, String> creds = new HashMap<>();
    Gson gson = new Gson();
    for (String key : keys) {
      if (System.getenv(key) == null) {
        throw new StorageException(
            HttpStatus.UNAUTHORIZED.value(),
            String.format(
                "Environment variable %s not defined. Hence authentication cannot be provided",
                key));
      }
      creds.put(key.toLowerCase(), System.getenv(key));
    }
    return GoogleCredentials.fromStream(
        new ByteArrayInputStream(gson.toJson(creds).replace("\\n", "\n").getBytes()));
  }

  /**
   * Pushes validation report to Google Cloud Storage. Returns the {@code HttpStatus} of the
   * validation report storage process. Requires authentication to be set prior execution i.e. the
   * following environment variables have to be defined:
   *
   * <ul>
   *   <li>TYPE
   *   <li>PROJECT_ID
   *   <li>PRIVATE_KEY_ID
   *   <li>PRIVATE_KEY
   *   <li>CLIENT_EMAIL
   *   <li>CLIENT_ID
   *   <li>AUTH_URI
   *   <li>TOKEN_URI
   *   <li>AUTH_PROVIDER_X509_CERT_URL
   *   <li>CLIENT_X509_CERT_URL
   *   <li>VALIDATION_REPORT_BUCKET
   * </ul>
   *
   * <p>Please check:
   *
   * <ul>
   *   <li><a href=https://cloud.google.com/storage/docs/naming-buckets>this documentation for
   *       bucket naming guidelines</a>
   *   <li><a
   *       href=https://cloud.google.com/docs/authentication/getting-started#setting_the_environment_variable>this
   *       documentation for authentication procedure</a>
   * </ul>
   *
   * @param commitSha the commit SHA attributed by Github. It is used to sort and store validation
   *     reportsin Google Cloud Storage.
   * @param datasetId the id of the dataset validated. It is arbitrarily determined by calling code,
   *     it is used to sort and store validation reports in Google Cloud Storage
   * @param args the {@code Argument} generated from the query parameters
   * @return the {@code HttpStatus} of the validation report storage process
   */
  private HttpStatus pushValidationReportToCloudStorage(
      String commitSha, String datasetId, Arguments args, StringBuilder messageBuilder) {
    HttpStatus status = HttpStatus.OK;
    try {
      if (System.getenv(GtfsValidatorController.VALIDATION_REPORT_BUCKET_NAME_ENV_VAR) == null) {
        throw new NullPointerException();
      }
      // Instantiates a client
      Storage storage =
          StorageOptions.newBuilder()
              .setCredentials(
                  generateCredentials(
                      TYPE,
                      PROJECT_ID,
                      PRIVATE_KEY_ID,
                      PRIVATE_KEY,
                      CLIENT_EMAIL,
                      CLIENT_ID,
                      AUTH_URI,
                      TOKEN_URI,
                      AUTH_PROVIDER_X509_CERT_URL,
                      CLIENT_X509_CERT_URL))
              .build()
              .getService();
      Bucket commitBucket =
          storage.get(
              System.getenv(GtfsValidatorController.VALIDATION_REPORT_BUCKET_NAME_ENV_VAR),
              Storage.BucketGetOption.fields());

      if (commitBucket == null) {
        commitBucket =
            storage.create(
                BucketInfo.newBuilder(System.getenv(VALIDATION_REPORT_BUCKET_NAME_ENV_VAR))
                    .setStorageClass(StorageClass.STANDARD)
                    .setLocation(DEFAULT_BUCKET_LOCATION)
                    .build());
        logger.atInfo().log("Bucket %s created.", commitBucket.getName());
      }
      BlobId blobId =
          BlobId.of(
              commitBucket.getName(),
              String.format("%s/%s/%s", commitSha, datasetId, args.getValidationReportName()));
      BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
      try (WriteChannel writer = storage.writer(blobInfo)) {
        writer.write(
            ByteBuffer.wrap(
                Files.readAllBytes(
                    Paths.get(
                        String.format(
                            "%s/%s", args.getOutputBase(), args.getValidationReportName())))));
      }
      messageBuilder.append(
          String.format(
              "Validation report successfully uploaded to %s/%s/%s/%s.\n",
              System.getenv(VALIDATION_REPORT_BUCKET_NAME_ENV_VAR),
              commitSha,
              datasetId,
              args.getValidationReportName()));
    } catch (NullPointerException nullPointerException) {
      status = HttpStatus.PRECONDITION_FAILED;
      messageBuilder.append(
          String.format(
              "Environment variable not provided: %s.\n", VALIDATION_REPORT_BUCKET_NAME_ENV_VAR));
      logger.atSevere().log(nullPointerException.getMessage());
    } catch (StorageException storageException) {
      status = HttpStatus.valueOf(storageException.getCode());
      messageBuilder.append(
          String.format(
              "Failure to upload validation report. %s\n", storageException.getMessage()));
      logger.atSevere().log(storageException.getMessage());
    } catch (IOException ioException) {
      status = HttpStatus.BAD_REQUEST;
      messageBuilder.append(
          String.format(
              "Failure to find validation report. Could not find %s/%s",
              args.getOutputBase(), args.getValidationReportName()));
      logger.atSevere().log(ioException.getMessage());
    } finally {
      buildResponseBody(messageBuilder, status);
    }
    return status;
  }

  /**
   * Generates response body and exports validation report
   *
   * @param messageBuilder additional information about the request' status
   * @param status the status of the request after execution
   * @param noticeContainer the {@code NoticeContainer} to extract {@code Notice}s from
   * @param args the {@code Arguments} to use for report export
   * @return the {@code ResponseEntity} that corresponds to the request
   */
  private static ResponseEntity<HashMap<String, String>> generateResponseAndExportReport(
      StringBuilder messageBuilder,
      HttpStatus status,
      NoticeContainer noticeContainer,
      Arguments args) {
    exportReport(noticeContainer, args);
    return generateResponse(messageBuilder, status);
  }

  /**
   * Generates response body
   *
   * @param messageBuilder the message to be displayed
   * @param status the status of the request after execution
   * @return the {@code ResponseEntity} that corresponds to the request
   */
  private static ResponseEntity<HashMap<String, String>> generateResponse(
      StringBuilder messageBuilder, HttpStatus status) {
    return new ResponseEntity<>(buildResponseBody(messageBuilder, status), status);
  }

  /**
   * Builds the response body as a {@code HashMap<String, String>} that contains information about
   * the request' status and additional information as a message.
   *
   * @param messageBuilder additional information about the request as amessage
   * @param status the request's status
   * @return the response body as a {@code HashMap<String, String>} that contains information about
   *     the request' status and additional information as a message.
   */
  private static HashMap<String, String> buildResponseBody(
      StringBuilder messageBuilder, HttpStatus status) {
    HashMap<String, String> root = new HashMap<>();
    root.put(MESSAGE_JSON_KEY, messageBuilder.toString());
    root.put(STATUS_JSON_KEY, status.toString());
    return root;
  }
}
