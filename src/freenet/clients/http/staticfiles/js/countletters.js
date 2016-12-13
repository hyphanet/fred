// from http://jsfiddle.net/AvH3Q/158/
function countletters(textid, countid){
    var maxchar = 1023;
    var i = document.getElementById(textid);
    var c = document.getElementById(countid);
    c.innerHTML = maxchar;
    
    i.addEventListener("keydown",count);
    i.addEventListener("keyup",count);
    i.addEventListener("compositionstart",count);
    i.addEventListener("compositionupdate",count);
    i.addEventListener("compositionend",count);
    
    function count(e){
        var len =  getUTF8Length(i.value);
        if (len >= maxchar){
            c.innerHTML = "<span style=\"color: red\">" + (maxchar - len-1) + "</style>";
        } else {
            c.innerHTML = maxchar - len-1;
        }
    }
    
    function getUTF8Length(s) {
        var len = 0;
        for (var i = 0; i < s.length; i++) {
            var code = s.charCodeAt(i);
            if (code <= 0x7f) {
                len += 1;
            } else if (code <= 0x7ff) {
                len += 2;
            } else if (code >= 0xd800 && code <= 0xdfff) {
                // Surrogate pair: These take 4 bytes in UTF-8 and 2 chars in UCS-2
                // (Assume next char is the other [valid] half and just skip it)
                len += 4; i++;
            } else if (code < 0xffff) {
                len += 3;
            } else {
                len += 4;
            }
        }
        return len;
    }
}
