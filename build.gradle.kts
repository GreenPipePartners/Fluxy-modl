// SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
// SPDX-License-Identifier: MPL-2.0

import java.security.MessageDigest

plugins {
    base
    id("io.ia.sdk.modl") version("0.1.1")
}

val releaseVersion = "0.1.4"
val buildNumber = "20260714"
val moduleVersionValue = "$releaseVersion.$buildNumber"
val repositoryUrl = "https://github.com/GreenPipePartners/Fluxy-modl"
val iaModulePrefix = "partners.greenpipe"
val moduleId = "$iaModulePrefix.fluxy"
val publicRelease = providers.gradleProperty("publicRelease").map(String::toBoolean).orElse(false)
val officialRelease = providers.gradleProperty("officialRelease").map(String::toBoolean).orElse(false)
val releaseBuild = publicRelease.get() || officialRelease.get()
// Replace only after IA provisions and documents version entitlement parameters.
val iaLicensingReady = false
val sourceCommit = providers.gradleProperty("sourceCommit").orElse("UNRELEASED")
val sourceTag = providers.gradleProperty("sourceTag").orElse("UNRELEASED")
val ignitionTarget = providers.gradleProperty("ignitionTarget").orElse("8.3")
val licenseMode = providers.gradleProperty("licenseMode").orElse("free")
val isIgnition81 = ignitionTarget.map { it.startsWith("8.1") }
val freeVariant = licenseMode.map { it == "free" }
val targetSuffix = if (isIgnition81.get()) "Ignition81" else "Ignition83"
val variantSuffix = if (freeVariant.get()) "-Free" else ""
val targetProject = if (isIgnition81.get()) ":gateway81" else ":gateway"
val targetIgnitionVersion = when (ignitionTarget.get()) {
    "8.1" -> "8.1.50"
    "8.3" -> "8.3.4"
    else -> ignitionTarget.get()
}
val moduleFileName = "Fluxy-$targetSuffix$variantSuffix"
val moduleDisplayName = if (freeVariant.get()) "Fluxy Free" else "Fluxy"

require(ignitionTarget.get() in setOf("8.1", "8.1.50", "8.1.51", "8.3", "8.3.4", "8.3.6")) {
    "ignitionTarget must be one of: 8.1, 8.1.50, 8.1.51, 8.3, 8.3.4, 8.3.6"
}
require(licenseMode.get() in setOf("licensed", "free")) {
    "licenseMode must be one of: licensed, free"
}
require(!(publicRelease.get() && officialRelease.get())) {
    "Choose either publicRelease or officialRelease, not both"
}
require(!publicRelease.get() || freeVariant.get()) {
    "Public releases must use -PlicenseMode=free"
}
require(moduleId.startsWith("$iaModulePrefix.")) {
    "Module ID must use IA-assigned prefix $iaModulePrefix"
}

extra["fluxyModuleVersion"] = moduleVersionValue
extra["fluxyReleaseVersion"] = releaseVersion
extra["fluxyBuildNumber"] = buildNumber
extra["fluxyRepositoryUrl"] = repositoryUrl
extra["fluxySourceCommit"] = sourceCommit.get()
extra["fluxySourceTag"] = sourceTag.get()
extra["fluxyTargetIgnitionVersion"] = targetIgnitionVersion
extra["fluxyLicenseMode"] = licenseMode.get()

allprojects {
    group = moduleId
    version = releaseVersion
}

val complianceDirectory = layout.buildDirectory.dir("generated/compliance")
val generateCompliance by tasks.registering {
    inputs.property("moduleVersion", moduleVersionValue)
    inputs.property("sourceCommit", sourceCommit)
    inputs.property("sourceTag", sourceTag)
    inputs.property("publicRelease", publicRelease)
    inputs.property("officialRelease", officialRelease)
    inputs.property("ignitionTarget", ignitionTarget)
    inputs.property("licenseMode", licenseMode)
    outputs.dir(complianceDirectory)

    doLast {
        val output = complianceDirectory.get().asFile
        output.mkdirs()
        val commit = sourceCommit.get()
        val tag = sourceTag.get()
        val sourceTree = if (commit.matches(Regex("[0-9a-f]{40}"))) {
            "$repositoryUrl/tree/$commit"
        } else {
            "$repositoryUrl/tree/main"
        }
        val sourceArchive = if (commit.matches(Regex("[0-9a-f]{40}"))) {
            "$repositoryUrl/archive/$commit.tar.gz"
        } else {
            "Not available for an uncommitted development build"
        }
        val sbomExternalReferences = if (commit.matches(Regex("[0-9a-f]{40}"))) {
            """
            |        {"type": "vcs", "url": "$sourceTree"},
            |        {"type": "distribution", "url": "$repositoryUrl/archive/$commit.tar.gz"}
            """.trimMargin()
        } else {
            "        {\"type\": \"vcs\", \"url\": \"$repositoryUrl\"}"
        }
        val status = when {
            officialRelease.get() -> "OFFICIAL IA-INTEGRATED RELEASE"
            publicRelease.get() -> "PUBLIC FREE RELEASE"
            else -> "UNSIGNED DEVELOPMENT BUILD - NOT FOR DISTRIBUTION"
        }
        val buildInstruction = if (releaseBuild) {
            "./gradlew -PignitionTarget=${ignitionTarget.get()} -PlicenseMode=${licenseMode.get()} " +
                "-PpublicRelease=${publicRelease.get()} -PofficialRelease=${officialRelease.get()} " +
                "-PsourceCommit=$commit -PsourceTag=$tag clean test packageReleaseCandidate"
        } else {
            "./gradlew -PignitionTarget=${ignitionTarget.get()} " +
                "-PlicenseMode=${licenseMode.get()} clean test packageDevelopment"
        }

        output.resolve("SOURCE.txt").writeText(
            """
            |Fluxy Ignition Gateway Module Corresponding Source
            |===================================================
            |
            |Status: $status
            |Module version: $moduleVersionValue
            |Ignition target: $targetIgnitionVersion
            |License mode: ${licenseMode.get()}
            |Source repository: $repositoryUrl
            |Source commit: $commit
            |Source tag: $tag
            |Source tree: $sourceTree
            |Source archive: $sourceArchive
            |
            |Preferred source is the complete repository at the exact commit above.
            |Build with Java 17 and the committed Gradle wrapper:
            |
            |  $buildInstruction
            |
            |Corresponding source remains available under MPL-2.0.
            |See LICENSE, NOTICE, and THIRD_PARTY_NOTICES.md in this distribution.
            |""".trimMargin()
        )
        output.resolve("fluxy-module-$moduleVersionValue.cdx.json").writeText(
            """
            |{
            |  "bomFormat": "CycloneDX",
            |  "specVersion": "1.6",
            |  "version": 1,
            |  "metadata": {
            |    "component": {
            |      "type": "application",
            |      "group": "$moduleId",
            |      "name": "fluxy-ignition-module",
            |      "version": "$moduleVersionValue",
            |      "licenses": [{"license": {"id": "MPL-2.0"}}],
            |      "properties": [
            |        {"name": "fluxy:ignitionTarget", "value": "$targetIgnitionVersion"},
            |        {"name": "fluxy:licenseMode", "value": "${licenseMode.get()}"}
            |      ],
            |      "externalReferences": [
            |$sbomExternalReferences
            |      ]
            |    }
            |  },
            |  "components": []
            |}
            |""".trimMargin()
        )
    }
}

ignitionModule {
    fileName.set(moduleFileName)
    name.set(moduleDisplayName)
    id.set(moduleId)
    moduleVersion.set(moduleVersionValue)
    moduleDescription.set(
        if (freeVariant.get()) {
            "Free, open-source Python 3 access to allowlisted Ignition Gateway operations."
        } else {
            "Authenticated Python 3 access to allowlisted Ignition scripting functions."
        }
    )
    requiredIgnitionVersion.set(targetIgnitionVersion)
    freeModule.set(freeVariant)
    license.set("LICENSE")
    metaInfo.put("vendor", "Green Pipe Partners, LLC")
    metaInfo.put("build", buildNumber)
    metaInfo.put("license", "MPL-2.0")
    metaInfo.put("sourceCommit", sourceCommit.get())
    metaInfo.put("sourceTag", sourceTag.get())
    metaInfo.put("ignitionTarget", targetIgnitionVersion)
    metaInfo.put("licenseMode", licenseMode.get())

    projectScopes.put(targetProject, "G")
    hooks.put("com.greenpipepartners.fluxy.gateway.FluxyGatewayHook", "G")

    // Release candidates are signed outside Gradle by the controlled IA module-signing tool.
    skipModlSigning.set(true)
}

tasks.named("assembleModlStructure") {
    dependsOn(generateCompliance)
    doLast {
        copy {
            from(
                layout.projectDirectory.file("README.md"),
                layout.projectDirectory.file("LICENSE"),
                layout.projectDirectory.file("NOTICE"),
                layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"),
                layout.projectDirectory.file("WEBDEV_MIT_NOTICE"),
                complianceDirectory,
            )
            into(layout.buildDirectory.dir("moduleContent"))
        }
    }
}

tasks.register<Copy>("packageDevelopment") {
    dependsOn("build")
    from(layout.buildDirectory.file("$moduleFileName.unsigned.modl"))
    into(rootProject.layout.projectDirectory.dir("release"))
    rename { "$moduleFileName-$moduleVersionValue-dev.unsigned.modl" }
    doLast {
        val artifact = layout.projectDirectory.file(
            "release/$moduleFileName-$moduleVersionValue-dev.unsigned.modl"
        ).asFile
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(artifact.readBytes())
            .joinToString("") { "%02x".format(it) }
        artifact.resolveSibling("${artifact.name}.sha256").writeText("$checksum  ${artifact.name}\n")
    }
}

val verifyReleaseInputs by tasks.registering {
    doLast {
        require(releaseBuild) {
            "Release packaging requires -PpublicRelease=true or -PofficialRelease=true"
        }
        if (publicRelease.get()) {
            require(freeVariant.get()) { "Public releases must be free modules" }
        }
        if (officialRelease.get()) {
            require(freeVariant.get() || iaLicensingReady) {
                "Licensed IA-integrated releases require completed IA licensing integration"
            }
        }
        require(sourceCommit.get().matches(Regex("[0-9a-f]{40}"))) {
            "Release packaging requires a 40-character -PsourceCommit"
        }
        require(sourceTag.get() == "v$moduleVersionValue") {
            "sourceTag must be v$moduleVersionValue"
        }
        val provenance = layout.projectDirectory.file("PROVENANCE.md").asFile.readText()
        require(!provenance.contains("BLOCKS EXTERNAL MODULE RELEASE")) {
            "Release packaging is blocked by unresolved ownership"
        }
        require(layout.projectDirectory.file("LICENSES/Gradle-8.2.1.txt").asFile.isFile) {
            "The Gradle wrapper license and notices must be present"
        }

        fun git(vararg arguments: String): String {
            val process = ProcessBuilder(listOf("git") + arguments)
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            require(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
            return output
        }

        require(git("status", "--porcelain").isEmpty()) { "Releases require a clean worktree" }
        require(git("rev-parse", "HEAD") == sourceCommit.get()) { "sourceCommit must equal HEAD" }
        require(git("rev-parse", "${sourceTag.get()}^{commit}") == sourceCommit.get()) {
            "sourceTag must resolve to sourceCommit"
        }
        require(git("remote", "get-url", "origin").removeSuffix(".git") == repositoryUrl) {
            "origin must be $repositoryUrl"
        }
        val remoteTag = git(
            "ls-remote",
            "--tags",
            "origin",
            "refs/tags/${sourceTag.get()}",
            "refs/tags/${sourceTag.get()}^{}",
        )
        require(remoteTag.lineSequence().any { it.startsWith(sourceCommit.get()) }) {
            "sourceTag must be pushed to origin and resolve to sourceCommit"
        }
    }
}

if (releaseBuild) {
    generateCompliance.configure {
        dependsOn(verifyReleaseInputs)
    }
}

tasks.register<Copy>("packageReleaseCandidate") {
    dependsOn(verifyReleaseInputs, "build")
    from(layout.buildDirectory.file("$moduleFileName.unsigned.modl"))
    into(rootProject.layout.projectDirectory.dir("release"))
    rename { "$moduleFileName-$moduleVersionValue.unsigned.modl" }
    doLast {
        val artifact = layout.projectDirectory.file(
            "release/$moduleFileName-$moduleVersionValue.unsigned.modl"
        ).asFile
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(artifact.readBytes())
            .joinToString("") { "%02x".format(it) }
        artifact.resolveSibling("${artifact.name}.sha256").writeText("$checksum  ${artifact.name}\n")
    }
}

tasks.named("writeModuleXml") {
    doLast {
        val moduleXml = layout.buildDirectory.file("moduleContent/module.xml").get().asFile
        val marker = "\t\t<requiredIgnitionVersion>"
        val vendorXml = """
            |		<vendorName>Green Pipe Partners, LLC</vendorName>
            |		<vendorContactInfo>https://greenpipe.partners/</vendorContactInfo>
            |
        """.trimMargin()
        val content = moduleXml.readText()
        if (!content.contains("<vendorName>")) {
            require(content.contains(marker)) { "Unable to locate module.xml vendor insertion point" }
            moduleXml.writeText(content.replaceFirst(marker, vendorXml + marker))
        }
    }
}
