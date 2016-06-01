function sanitizeFormInputKey(thisForm) {
    var url = thisForm.elements['key'].value.split('#')[0];
    var anchor = thisForm.elements['key'].value.split('#')[1];
    /* Remove default host + port prefix */
    if (url.startsWith("http://127.0.0.1:8888/")) {
        url = url.replace("http://127.0.0.1:8888/", "");
    } else if (url.startsWith("https://127.0.0.1:8888/")) {
        url = url.replace("https://127.0.0.1:8888/", "");
    } else if (url.startsWith("http://localhost:8888/")) {
        url = url.replace("http://localhost:8888/", "");
    } else if (url.startsWith("https://localhost:8888/")) {
        url = url.replace("https://localhost:8888/", "");
    }
    /* Move anchor into hidden input to prevent escaping the # */
    if (anchor != null) {
        var anch = document.createElement('INPUT');
        thisForm.appendChild(anch);
        anch.type = 'hidden';
        anch.name = "anchor";
        anch.value = anchor;
        thisForm.elements['key'].value = url;
    };
};
