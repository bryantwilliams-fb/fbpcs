/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#include <cstdint>
#include <filesystem>
#include <string>
#include <unordered_map>
#include <vector>

#include <gtest/gtest.h>

#include <fbpcf/mpc/EmpGame.h>
#include "folly/Format.h"
#include "folly/Random.h"
#include "folly/logging/xlog.h"

#include "fbpcs/emp_games/common/TestUtil.h"
#include "fbpcs/emp_games/attribution/decoupled_attribution/test/AttributionTestUtils.h"

namespace aggregation::private_attribution {

class AttributionAppTest : public ::testing::Test {
 protected:
  void SetUp() override {
    port_ = 5000 + folly::Random::rand32() % 1000;
    baseDir_ = private_measurement::test_util::getBaseDirFromPath(__FILE__);
    std::string tempDir = std::filesystem::temp_directory_path();
    serverIpAlice_ = "";
    serverIpBob_ = "127.0.0.1";
    outputPathAlice_ = folly::sformat(
        "{}/output_path_alice.json_{}", tempDir, folly::Random::secureRand64());
    outputPathBob_ = folly::sformat(
        "{}/output_path_bob.json_{}", tempDir, folly::Random::secureRand64());
  }

  void TearDown() override {
    std::filesystem::remove(outputPathAlice_);
    std::filesystem::remove(outputPathBob_);
  }

  std::string serverIpAlice_;
  std::string serverIpBob_;
  uint16_t port_;
  std::string baseDir_;
  std::string outputPathAlice_;
  std::string outputPathBob_;
};

// Test cases are iterate in https://fb.quip.com/IUHDApxKEAli
TEST_F(AttributionAppTest, TestMPCAEMCorrectnessWithPrivateScaling) {
  // Attribution rules we want to test
  std::vector<std::string> attribution_rules{"last_click_1d", "last_touch_1d"};

  for (auto attribution_rule : attribution_rules) {
    // modifiable input parameters
    std::string inputPrefix = "test_correctness";
    // inputPrefix should is sufficient in specifying the right input data
    // for both Alice (publisher) and bob (partner)
    std::string attributionRuleAlice = attribution_rule;
    std::string attributionRuleBob = "";
    std::string aggregatorBob = "";

    std::string OutputJsonFileName =
        baseDir_ + inputPrefix + "/" + attributionRuleAlice + ".json";

    auto [resAlice, resBob] = runGameAndGenOutputXOR(
        serverIpAlice_,
        port_,
        attributionRuleAlice,
        baseDir_ + inputPrefix + "/" + attributionRuleAlice + ".publisher.csv",
        outputPathAlice_,
        serverIpBob_,
        port_,
        attributionRuleBob,
        baseDir_ + inputPrefix + "/" + attributionRuleAlice + ".partner.csv",
        outputPathBob_);

    // for XORed cases, additional step to decode real answer
    auto [revealedResAlice, revealedresBob] =
        revealXORedResult(resAlice, resBob, attributionRuleAlice);

    // verify whether the output is what we expected
    verifyOutput(revealedResAlice, revealedresBob, OutputJsonFileName);
  }
}
} // namespace aggregation::private_attribution
