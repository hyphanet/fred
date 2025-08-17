// @license magnet:?xt=urn:btih:1f739d935676111cfff4b4693e3816e664797050&dn=gpl-3.0.txt GPL-v3-or-Later
function addCopyKeyFieldToFinishedTransfers() {
  if (navigator && navigator.clipboard) {
    var matching = document.getElementsByClassName("finished_key_link");
    for (var matchingElement of matching) {
      var key = matchingElement.dataset.key;
      if (key) {
        var toClipboard = document.createElement("span");
        toClipboard.textContent = " ⎘";
        toClipboard.classList.add("copy-to-clipboard-element");
        toClipboard.setAttribute("title", "Copy to Clipboard");
        toClipboard.onclick=() => navigator.clipboard.writeText(key);
        matchingElement.parentElement.appendChild(toClipboard);
      }
    }
  }
}
document.addEventListener("DOMContentLoaded", addCopyKeyFieldToFinishedTransfers);
// @license-end
