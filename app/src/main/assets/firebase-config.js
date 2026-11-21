// Firebase Configuration
export const firebaseConfig = {
  apiKey: "AIzaSyCjhP79Vq25uYwMPzBOs-MUv92-MF5D-ms",
  authDomain: "examresult-21cf3.firebaseapp.com",
  databaseURL: "https://examresult-21cf3-default-rtdb.firebaseio.com",
  projectId: "examresult-21cf3",
  storageBucket: "examresult-21cf3.firebasestorage.app",
  messagingSenderId: "889924282994",
  appId: "1:889924282994:web:9d3a305af145228cce70eb"
};

if (typeof window !== 'undefined') {
  window.firebaseConfig = firebaseConfig;
}
