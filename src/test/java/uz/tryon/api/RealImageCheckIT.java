package uz.tryon.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * USM-152: Real-image /api/check integration test.
 * Confirms multi-signal back/side/front detection with actual MoveNet ONNX keypoints.
 * Images sourced in docs/test-images/ (front.jpg, back.jpg, side.jpg).
 */
@SpringBootTest
@ActiveProfiles("test")
class RealImageCheckIT {

    @Autowired
    ImageCheckService checkService;

    private String loadImage(String filename) throws Exception {
        Path path = Paths.get("docs/test-images", filename);
        byte[] bytes = Files.readAllBytes(path);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void printReport(String label, CheckReport report) {
        System.out.printf("%n=== %s ===%n", label);
        for (CheckItem c : report.checks()) {
            System.out.printf("  %-20s %-6s  %s%n", c.id(), c.status(), c.message());
        }
        System.out.printf("  overall ok=%-5b  summary=%s%n", report.ok(), report.summary());
    }

    @Test
    void frontFacing_posePass() throws Exception {
        CheckReport report = checkService.check(loadImage("front.jpg"), "upper");
        printReport("FRONT (front.jpg)", report);

        Optional<CheckItem> pose = report.checks().stream()
                .filter(c -> "pose".equals(c.id())).findFirst();

        assertThat(pose).as("pose check must run (earlier checks must all pass)").isPresent();
        assertThat(pose.get().status())
                .as("front-facing image => pose should PASS, got: " + pose.map(CheckItem::message).orElse("N/A"))
                .isEqualTo(CheckItem.PASS);
    }

    @Test
    void backFacing_poseFail() throws Exception {
        CheckReport report = checkService.check(loadImage("back.jpg"), "upper");
        printReport("BACK (back.jpg)", report);

        Optional<CheckItem> pose = report.checks().stream()
                .filter(c -> "pose".equals(c.id())).findFirst();

        assertThat(pose).as("pose check must run (earlier checks must all pass)").isPresent();
        assertThat(pose.get().status())
                .as("back-facing image => pose should FAIL, got: " + pose.map(CheckItem::message).orElse("N/A"))
                .isEqualTo(CheckItem.FAIL);
        assertThat(pose.get().message())
                .as("back-facing => must include Uzbek 'turn around' warning")
                .contains("Orqasi bilan turmang");
    }

    @Test
    void sideFacing_poseWarn() throws Exception {
        CheckReport report = checkService.check(loadImage("side.jpg"), "upper");
        printReport("SIDE (side.jpg)", report);

        Optional<CheckItem> pose = report.checks().stream()
                .filter(c -> "pose".equals(c.id())).findFirst();

        assertThat(pose).as("pose check must run (earlier checks must all pass)").isPresent();
        assertThat(pose.get().status())
                .as("side-facing image => pose should WARN, got: " + pose.map(CheckItem::message).orElse("N/A"))
                .isEqualTo(CheckItem.WARN);
    }
}
