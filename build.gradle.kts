import com.tonic.vitahub.build.Release
import com.tonic.vitahub.build.Signing
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

val signingKey: Path = (findProperty("signingKey") as String?)?.let { Paths.get(it) }
    ?: Paths.get(System.getProperty("user.home"), ".vitalite", "hub-signing.pem")

fun releaseOptions(): Release.Options {
    val options = Release.Options()
    options.root = projectDir.toPath()
    options.privateKey = signingKey
    options.repository = (findProperty("repository") as String?) ?: Release.originRepository(projectDir.toPath())
        ?: throw GradleException("no GitHub remote named origin; pass -Prepository=owner/repo")
    options.manifestName = (findProperty("hubName") as String?) ?: "My Plugins"
    options.sign = (findProperty("hubSigned") as String?).toBoolean()
    options.gh = (findProperty("gh") as String?) ?: Release.defaultGh()
    options.dryRun = hasProperty("dryRun")
    options.localUrls = hasProperty("localUrls")
    return options
}

tasks.register("generateSigningKey") {
    group = "vita hub"
    description = "Creates the official signing key outside the repository and prints the public key VitaLite embeds"
    doLast {
        val publicKey = Signing.generate(signingKey)
        Files.writeString(projectDir.toPath().resolve("hub-public-key.txt"), publicKey + "\n")
        println("Private key written to $signingKey (keep it, never commit it)")
        println("Public key (also in hub-public-key.txt, paste into VitaLite's HubSignature):")
        println(publicKey)
    }
}

tasks.register("buildPlugins") {
    group = "vita hub"
    description = "Builds every catalog plugin from its own project into build/release"
    doLast {
        Release.buildAll(releaseOptions())
    }
}

tasks.register("publishRelease") {
    group = "vita hub"
    description = "Releases new plugin versions, then writes (signed when hubSigned=true), commits and pushes plugins.json (-PdryRun: no release, commit or push; -PlocalUrls: file: URLs for testing)"
    doLast {
        Release.publish(releaseOptions())
    }
}

tasks.register("verifyManifest") {
    group = "vita hub"
    description = "Checks plugins.json against its signature and the jars in build/release against their hashes"
    doLast {
        val keyFile = projectDir.toPath().resolve("hub-public-key.txt")
        val publicKey = if (Files.isRegularFile(keyFile)) Files.readString(keyFile) else Files.readString(Signing.publicKeyFile(signingKey))
        Release.verify(projectDir.toPath(), publicKey)
    }
}
