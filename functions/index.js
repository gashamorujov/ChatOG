// OPTIONAL (recommended for 100% reliability on every phone brand):
// Sends a high-priority FCM data message whenever a record is written to
// userNotifications/{uid}/{notifId}. The Android app already stores each user's
// token at fcmTokens/{uid}/token and handles these data messages.
//
// Deploy:  npm i -g firebase-tools && firebase login
//          firebase use examresult-21cf3 && cd functions && npm i && cd .. && firebase deploy --only functions
// (Cloud Functions require the Blaze plan.)
const { onValueCreated } = require("firebase-functions/v2/database");
const admin = require("firebase-admin");
admin.initializeApp();

exports.pushOnNotification = onValueCreated(
  { ref: "/userNotifications/{uid}/{notifId}", region: "us-central1", instance: "examresult-21cf3-default-rtdb" },
  async (event) => {
    const { uid, notifId } = event.params;
    const n = event.data.val() || {};
    if (!n || n.senderUid === uid) return;
    const tokenSnap = await admin.database().ref(`fcmTokens/${uid}/token`).get();
    const token = tokenSnap.val();
    if (!token) return;
    const str = (v) => (v === undefined || v === null ? "" : String(v));
    try {
      await admin.messaging().send({
        token,
        android: { priority: "high", ttl: 60 * 1000 },
        data: {
          id: notifId,
          type: str(n.type || "message"),
          title: str(n.title),
          body: str(n.body),
          sender_name: str(n.senderName),
          sender_dp: str(n.senderDp).startsWith("http") ? str(n.senderDp) : "",
          sender_uid: str(n.senderUid),
          chat_id: str(n.chatId),
        },
      });
    } catch (e) {
      if (e.code === "messaging/registration-token-not-registered") {
        await admin.database().ref(`fcmTokens/${uid}`).remove();
      } else {
        console.error("FCM send failed", e);
      }
    }
  }
);
