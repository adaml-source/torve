/**
 * StreamVault — Rebate Code Redemption Cloud Function
 *
 * Deploy:
 *   cd firebase/functions
 *   npm install
 *   firebase deploy --only functions
 *
 * Firestore collection: rebate_codes
 * Document ID = the code string (e.g. "TORVE-ABCD-1234")
 *
 * Document schema:
 *   {
 *     code: "TORVE-ABCD-1234",
 *     used: false,
 *     usedBy: null,           // deviceId on redemption
 *     usedAt: null,           // Firestore Timestamp on redemption
 *     createdAt: Timestamp,
 *     expiresAt: Timestamp | null,
 *     note: "Press review – TechCrunch"
 *   }
 */

const { onRequest } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");

initializeApp();
const db = getFirestore();

// Rate-limit: max attempts per device per window
const MAX_ATTEMPTS = 5;
const WINDOW_MS = 60 * 60 * 1000; // 1 hour

exports.redeemCode = onRequest({ cors: true }, async (req, res) => {
  if (req.method !== "POST") {
    return res.status(405).json({ valid: false, error: "Method not allowed" });
  }

  const { code, deviceId } = req.body || {};

  if (!code || typeof code !== "string") {
    return res.status(400).json({ valid: false, error: "Missing code" });
  }
  if (!deviceId || typeof deviceId !== "string") {
    return res.status(400).json({ valid: false, error: "Missing deviceId" });
  }

  const sanitizedCode = code.trim().toUpperCase();

  // --- Rate limiting (per device) ---
  try {
    const rateLimitRef = db.collection("rate_limits").doc(deviceId);
    const rateLimitSnap = await rateLimitRef.get();

    if (rateLimitSnap.exists) {
      const data = rateLimitSnap.data();
      const windowStart = Date.now() - WINDOW_MS;
      const recentAttempts = (data.attempts || []).filter(
        (ts) => ts.toMillis() > windowStart
      );

      if (recentAttempts.length >= MAX_ATTEMPTS) {
        return res
          .status(429)
          .json({ valid: false, error: "Too many attempts. Try again later." });
      }
    }

    // Record this attempt
    await rateLimitRef.set(
      { attempts: FieldValue.arrayUnion(FieldValue.serverTimestamp()) },
      { merge: true }
    );
  } catch (e) {
    // Rate-limit check is best-effort; don't block redemption on failure
    console.warn("Rate limit check failed:", e.message);
  }

  // --- Code redemption (Firestore transaction) ---
  const codeRef = db.collection("rebate_codes").doc(sanitizedCode);

  try {
    const result = await db.runTransaction(async (tx) => {
      const doc = await tx.get(codeRef);

      if (!doc.exists) {
        return { valid: false, error: "Invalid code" };
      }

      const data = doc.data();

      if (data.used) {
        return { valid: false, error: "Code already used" };
      }

      if (data.expiresAt && data.expiresAt.toMillis() < Date.now()) {
        return { valid: false, error: "Code has expired" };
      }

      // Atomically mark as used
      tx.update(codeRef, {
        used: true,
        usedBy: deviceId,
        usedAt: FieldValue.serverTimestamp(),
      });

      return { valid: true, type: "free_lifetime" };
    });

    return res.status(result.valid ? 200 : 400).json(result);
  } catch (e) {
    console.error("Redemption error:", e);
    return res
      .status(500)
      .json({ valid: false, error: "Server error. Please try again." });
  }
});
