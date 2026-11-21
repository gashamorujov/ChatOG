# Push bildirişləri (tətbiq bağlı olanda) — bir dəfəlik quraşdırma

Tətbiq tam bağlı olanda bildiriş almağın yeganə düzgün yolu serverdən FCM göndərməkdir
(WhatsApp da belə işləyir). Bunun üçün `functions/index.js` Firebase-ə bir dəfə yüklənməlidir.

Tələblər: Firebase layihəsi `examresult-21cf3` **Blaze** planında olmalıdır (kart tələb edir,
bu həcmdə praktiki olaraq pulsuzdur).

## Ən asan yol — brauzerdə (Google Cloud Shell)
1. https://shell.cloud.google.com açın (Firebase ilə eyni Google hesabı).
2. Aşağıdakıları yapışdırın:

```
git clone https://github.com/gashamorujov/ChatOG && cd ChatOG
npm i -g firebase-tools
firebase login --no-localhost
cd functions && npm install && cd ..
firebase deploy --only functions --project examresult-21cf3
```

Bitəndə `pushOnNotification` funksiyası görünəcək. Bundan sonra hər mesaj, zəng, dostluq
sorğusu avtomatik telefona push kimi gedəcək.
