// from http://jsfiddle.net/AvH3Q/158/
function countletters(i, c){
    var maxchar = 1023;
    c.innerHTML = maxchar;
    
    function getUTF8Length(s) {
        var len = 0;
        for (var j = 0; j < s.length; j++) {
            var code = s.charCodeAt(j);
            if (code <= 0x7f) {
                len += 1;
            } else if (code <= 0x7ff) {
                len += 2;
            } else if (code >= 0xd800 && code <= 0xdfff) {
                // Surrogate pair: These take 4 bytes in UTF-8 and 2 chars in UCS-2
                // (Assume next char is the other [valid] half and just skip it)
                len += 4; j++;
            } else if (code < 0xffff) {
                len += 3;
            } else {
                len += 4;
            }
        }
        return len;
    }

    function count(e){
        var len =  getUTF8Length(i.value);
        if (len >= maxchar){
            c.innerHTML = "<span style=\"color: red\">" + (maxchar - len-1) + "</style>";
        } else {
            c.innerHTML = maxchar - len-1;
        }
    }
    
    i.addEventListener("keydown",count);
    i.addEventListener("keyup",count);
    i.addEventListener("compositionstart",count);
    i.addEventListener("compositionupdate",count);
    i.addEventListener("compositionend",count);
}

function starttextcounter(){
    var containerclass = "n2ntmcountedtext";
    var textareaclass = "n2ntmtextinput";
    var counterclass = "n2ntmcount";
    var containers = document.getElementsByClassName(containerclass);
    for (var j = 0; j < containers.length; j++) {
        var container = containers.item(j);
        var textarea = container.getElementsByClassName(textareaclass)[0];
        var counter = container.getElementsByClassName(counterclass)[0];
        countletters(textarea, counter);
    }
}

document.addEventListener("DOMContentLoaded", starttextcounter, false);
