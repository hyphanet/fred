// @license magnet:?xt=urn:btih:1f739d935676111cfff4b4693e3816e664797050&dn=gpl-3.0.txt GPL-v3-or-Later
if (typeof(document.addCopyKeyFieldToFinishedTransfers) === 'undefined') {
  function addCopyKeyFieldToFinishedTransfers () {
    if (navigator && navigator.clipboard) {
      var matching = document.getElementsByClassName('copy-to-clipboard');
      for (var matchingElement of matching) {
        // must use const to avoid the weird scoping of var replacing all text with the last element
        // const for declaration is usable in IE11+.
        const text = matchingElement.dataset.copytext;
        if (text) {
          var toClipboard = document.createElement('span');
          // SVG image thanks to bertm!
          toClipboard.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" style="height: 1em; width: 1em; margin-inline-start: .5ex; vertical-align: middle;"><path stroke="currentColor" stroke-width="2" d="M 2 16 v -12 a 2 2 0 0 1 2 -2 h 12 M 9 7 h 10 a 2 2 0 0 1 2 2 v 10 a 2 2 0 0 1 -2 2 h -10 a 2 2 0 0 1 -2 -2 v -10 a 2 2 0 0 1 2 -2" fill="none"></path></svg>';
          toClipboard.classList.add('copy-to-clipboard-element');
          toClipboard.setAttribute('title', matchingElement.dataset.copytitle);
          toClipboard.onclick = () => navigator.clipboard.writeText(text);
          matchingElement.after(toClipboard);
        }
      }
    }
  }
  document.addCopyKeyFieldToFinishedTransfers = addCopyKeyFieldToFinishedTransfers;
  document.addEventListener('DOMContentLoaded', addCopyKeyFieldToFinishedTransfers);
}
// @license-end
