package top.tianyan.app.runtime.shell

import org.junit.Assert.*
import org.junit.Test

class CommandResourcePolicyTest {
    @Test fun knownBuildAndAnalysisInvocationsReserveMemory() {
        listOf("./gradlew :app:assembleDebug", "gradle --version assembleRelease", "flutter build apk",
            "cargo check", "go test ./...", "npm run build", "pnpm exec tsc", "npx vite build",
            "java -jar compiler.jar", "jadx app.apk", "apktool b app", "cmake --build out",
            "rustc source.rs", "ninja", "/opt/tianyan/scripts/tianyan-build.sh android /workspace/p").forEach {
            assertTrue(it, CommandResourcePolicy.isHeavy(it))
        }
    }

    @Test fun quotedPatternsAndKnownInspectionActionsStayLight() {
        listOf("echo 'gradle build; flutter build apk'", "rg -n 'java -jar' README.md",
            "cat /workspace/gradlew", "ls gradle", "gradle --version", "gradle --status",
            "java -version", "rustc -vV", "cargo fmt", "go env", "npm config list",
            "flutter devices", "echo ok # gradle build", "echo \"text; jadx app.apk\"").forEach {
            assertFalse(it, CommandResourcePolicy.isHeavy(it))
        }
    }

    @Test fun assignmentsWrappersAndShellPayloadsPreserveHeavyClassification() {
        listOf("JAVA_HOME='/opt/jdk' '/workspace/gradlew' build", "env -u TOKEN nice -n 5 gradle build",
            "cd '/workspace/my app' && ./gradlew build", "printf ok | gradle build",
            "bash -lc 'cd /workspace/p; gradle test'", "if test -f gradlew; then ./gradlew build; fi",
            "build() { ./gradlew build; }; build", "rtk cargo build",
            "/bin/sh /opt/tianyan/scripts/tianyan-build.sh android /workspace/p").forEach {
            assertTrue(it, CommandResourcePolicy.isHeavy(it))
        }
        assertFalse(CommandResourcePolicy.isHeavy("sh -c \"echo 'gradle build'\""))
    }

    @Test fun trustedScriptHintCanOnlyEscalateAdmission() {
        assertTrue(CommandResourcePolicy.needsMemorySlot(ShellCommand("/bin/sh arbitrary-workshop-script.sh", memoryHeavy = true)))
        assertTrue(CommandResourcePolicy.needsMemorySlot(ShellCommand("gradle build", memoryHeavy = false)))
        assertFalse(CommandResourcePolicy.needsMemorySlot(ShellCommand("git status")))
    }

    @Test fun pathologicalInputIsBoundedAndConservativelyReservesMemory() {
        assertTrue(CommandResourcePolicy.isHeavy("echo " + "x".repeat(70_000)))
    }
}
