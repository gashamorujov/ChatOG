// WhatsApp-style push: whenever a notification record is written to
// userNotifications/{uid}/{notifId} (message, call, friend request ...),
// this function sends a high-priority FCM data message to the recipient's phone.
// The Android app already stores the token at fcmTokens/{uid}/token and shows the
// notification + launcher badge even when the app is fully closed.
const { onValueCreated } = require("firebase-functions/v2/database");
const admin = require("firebase-admin");
admin.initializeApp();

const str = (v) => (v === undefined || v === null ? "" : String(v));

exports.pushOnNotification = onValueCreated(
  {
    ref: "/userNotifications/{uid}/{notifId}",
    instance: "examresult-21cf3-default-rtdb",
    region: "us-central1",
  },
  async (event) => {
    const { uid, notifId } = event.params;
    const n = event.data.val() || {};
    if (!n || n.senderUid === uid) return;

    const tokenSnap = await admin.database().ref(`fcmTokens/${uid}/token`).get();
    const token = tokenSnap.val();
    if (!token) return;

    const isCall = n.type === "call" || n.type === "incoming_call";
    const dp = str(n.senderDp);
    try {
      await admin.messaging().send({
        token,
        android: { priority: "high", ttl: isCall ? 30 * 1000 : 24 * 60 * 60 * 1000 },
        data: {
          id: notifId,
          type: str(n.type || "message"),
          title: str(n.title),
          body: str(n.body),
          sender_name: str(n.senderName),
          sender_dp: dp.startsWith("http") ? dp : "",
          sender_uid: str(n.senderUid),
          chat_id: str(n.chatId),
          call_id: str(n.callId),
        },
      });
    } catch (e) {
      if (e.code === "messaging/registration-token-not-registered" ||
          e.code === "messaging/invalid-registration-token") {
        await admin.database().ref(`fcmTokens/${uid}`).remove();
      } else {
        console.error("FCM send failed", e);
      }
    }
  }
);
