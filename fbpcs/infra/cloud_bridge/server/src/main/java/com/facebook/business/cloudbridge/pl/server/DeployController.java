/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.business.cloudbridge.pl.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeployController {
  private final Logger logger = LoggerFactory.getLogger(DeployController.class);

  private DeploymentRunner runner;
  private Semaphore singleProvisioningLock = new Semaphore(1);

  @PostMapping(
      path = "/v1/deployment",
      consumes = "application/json",
      produces = "application/json")
  public APIReturn deploymentCreate(@RequestBody DeploymentParams deployment) {
    logger.info("Received deployment request: " + deployment.toString());
    return runDeployment(true, deployment);
  }

  @DeleteMapping(
      path = "/v1/deployment",
      consumes = "application/json",
      produces = "application/json")
  public APIReturn deploymentDelete(@RequestBody DeploymentParams deployment) {
    logger.info("Received undeployment request: " + deployment.toString());
    return runDeployment(false, deployment);
  }

  private APIReturn runDeployment(boolean shouldDeploy, DeploymentParams deployment) {
    try {
      deployment.validate();
    } catch (InvalidDeploymentArgumentException ex) {
      return new APIReturn(APIReturn.Status.STATUS_FAIL, ex.getMessage());
    }
    logger.info("  Validated input");

    try {
      if (!singleProvisioningLock.tryAcquire()) {
        String errorMessage = "Another deployment is in progress";
        logger.error("  " + errorMessage);
        return new APIReturn(APIReturn.Status.STATUS_FAIL, errorMessage);
      }
      logger.info("  No deployment conflicts found");

      runner =
          new DeploymentRunner(
              shouldDeploy,
              deployment,
              () -> {
                singleProvisioningLock.release();
              });
      runner.start();
      if (shouldDeploy) {
        return new APIReturn(APIReturn.Status.STATUS_SUCCESS, "Deployment Started Successfully");
      } else {
        return new APIReturn(APIReturn.Status.STATUS_SUCCESS, "Undeployment Started Successfully");
      }
    } catch (DeploymentException ex) {
      return new APIReturn(APIReturn.Status.STATUS_ERROR, ex.getMessage());
    } finally {
      logger.info("  Deployment request finalized");
    }
  }

  @GetMapping(path = "/v1/deployment", produces = "application/json")
  public APIReturn deploymentStatus() {
    logger.info("Received status request");

    DeploymentRunner.DeploymentState state;
    String output;

    ObjectMapper mapper = new ObjectMapper();
    ObjectNode rootNode = mapper.createObjectNode();
    if (runner == null) {
      logger.debug("  No deployment created yet");
      output = "";
    } else {
      state = runner.getDeploymentState();
      output = runner.getOutput();

      rootNode.put("state", state.toString());
      if (state == DeploymentRunner.DeploymentState.STATE_HALTED) {
        rootNode.put("exitValue", runner.getExitValue());
        runner = null;
      }
    }

    return new APIReturn(APIReturn.Status.STATUS_SUCCESS, output, rootNode);
  }

  @GetMapping(path = "/v1/deployment/logs")
  public byte[] downloadDeploymentLogs() {
    logger.info("Received logs request");
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    try {
      ZipOutputStream zout = new ZipOutputStream(bo);
      compressIfExists("/tmp/server.log", "server.log", zout);
      compressIfExists("/tmp/deploy.log", "deploy.log", zout);
      compressIfExists("/tmp/terraform.log", "terraform.log", zout);
      zout.close();
    } catch (IOException e) {
      logger.debug(
          "  Could not compress logs. Message: " + e.getMessage() + "\n" + e.getStackTrace());
    }

    logger.info("Logs request finalized");
    return bo.toByteArray();
  }

  private void compressIfExists(String fullFilePath, String zipName, ZipOutputStream zout) {
    Path file = Paths.get(fullFilePath);
    logger.info("  Compressing \"" + fullFilePath + "\"");
    if (Files.exists(file)) {
      logger.trace("  File exists");
      try {
        byte[] bytes = Files.readAllBytes(file);
        ZipEntry ze = new ZipEntry(zipName);
        zout.putNextEntry(ze);
        zout.write(bytes);
        zout.closeEntry();
      } catch (IOException e) {
        logger.debug(
            "  Could not read file. Message: " + e.getMessage() + "\n" + e.getStackTrace());
      }
    } else {
      logger.debug("  File does not exist");
    }
  }
}
