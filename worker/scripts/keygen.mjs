/**
 * Generate the relay's HPKE keypair.
 *
 *   node scripts/keygen.mjs
 *
 * The private key goes in as a Worker secret and nowhere else:
 *   npx wrangler secret put RELAY_PRIVATE_KEY
 *
 * The public key is compiled into the Android app, which pins it at enrolment.
 * A changed relay key is a blocking, visible event on the device rather than a
 * silent redirect (THREAT-MODEL A6), so rotating it means shipping an app build.
 */
import { CipherSuite, HkdfSha256, DhkemX25519HkdfSha256 } from "@hpke/core";
import { Chacha20Poly1305 } from "@hpke/chacha20poly1305";

const b64url = (buf) =>
  Buffer.from(buf).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

const suite = new CipherSuite({
  kem: new DhkemX25519HkdfSha256(),
  kdf: new HkdfSha256(),
  aead: new Chacha20Poly1305(),
});

const kp = await suite.kem.generateKeyPair();
const priv = await suite.kem.serializePrivateKey(kp.privateKey);
const pub = await suite.kem.serializePublicKey(kp.publicKey);

console.log("RELAY_PRIVATE_KEY (worker secret, never commit):");
console.log("  " + b64url(priv));
console.log("\nRELAY_PUBLIC_KEY (pin in the Android app):");
console.log("  " + b64url(pub));
