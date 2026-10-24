import {authorizationReturn} from './relay-core.mjs';

const message = document.getElementById('message');
const returnButton = document.getElementById('return');
const copyButton = document.getElementById('copy');
const originalUrl = window.location.href;
const originalSearch = window.location.search;
// Remove the one-time code from the visible URL/history; keep it only in this page's memory.
window.history.replaceState(null, '', window.location.pathname);
try {
  const result = authorizationReturn(originalSearch);
  message.textContent = `Authorization received. Return it to your TV at ${result.host}.`;
  returnButton.disabled = false;
  returnButton.addEventListener('click', () => window.location.replace(result.url));
  if (window.isSecureContext && navigator.clipboard?.writeText) {
    copyButton.hidden = false;
    copyButton.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(originalUrl);
        message.textContent = 'Copied. In the active MyAnimeList connection on your TV, choose Enter callback URL and paste this link.';
      } catch {
        message.textContent = 'Your browser did not allow copying. Start a fresh connection on the TV and try Return to Syclop.';
      }
    });
  }
} catch (error) {
  document.getElementById('title').textContent = 'Start again on your TV';
  message.textContent = error.message;
}
