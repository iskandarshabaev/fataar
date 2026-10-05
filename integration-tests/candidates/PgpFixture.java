import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.Date;
import org.bouncycastle.bcpg.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.*;

/** Disposable test identity. Never use this unencrypted key for a real publication. */
class PgpFixture {
    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        Path directory = Path.of(args[1]);
        if (args[0].equals("generate")) {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, generator.generateKeyPair(), new Date());
            var digest = new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1);
            var secret = new PGPSecretKey(PGPSignature.DEFAULT_CERTIFICATION, pair,
                    "FatAar disposable test <fixture@example.invalid>", digest, null, null,
                    new JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA256), null);
            try (var out = new ArmoredOutputStream(Files.newOutputStream(directory.resolve("test-secret.asc")))) {
                secret.encode(out);
            }
            try (var out = new ArmoredOutputStream(Files.newOutputStream(directory.resolve("test-public.asc")))) {
                secret.getPublicKey().encode(out);
            }
            System.out.println("Generated disposable signing identity");
        } else if (args[0].equals("verify")) {
            PGPPublicKeyRingCollection keys;
            try (var in = PGPUtil.getDecoderStream(Files.newInputStream(directory.resolve("test-public.asc")))) {
                keys = new PGPPublicKeyRingCollection(in, new JcaKeyFingerprintCalculator());
            }
            for (int index = 2; index < args.length; index++) {
                Path artifact = Path.of(args[index]);
                PGPSignature signature;
                try (var in = PGPUtil.getDecoderStream(Files.newInputStream(Path.of(args[index] + ".asc")))) {
                    signature = ((PGPSignatureList) new JcaPGPObjectFactory(in).nextObject()).get(0);
                }
                signature.init(new JcaPGPContentVerifierBuilderProvider(), keys.getPublicKey(signature.getKeyID()));
                try (var in = Files.newInputStream(artifact)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) signature.update(buffer, 0, count);
                }
                if (!signature.verify()) throw new AssertionError("Invalid signature: " + artifact);
                System.out.println("Verified " + artifact.getFileName());
            }
        } else throw new IllegalArgumentException(args[0]);
    }
}
