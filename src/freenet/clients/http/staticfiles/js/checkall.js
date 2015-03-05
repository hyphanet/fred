// copypaste javascript from http://stackoverflow.com/a/7251104/7666
function checkAll(bx, classname) {
  var cbs = document.getElementsByClassName(classname)[0].getElementsByTagName("input");
  for(var i=0; i < cbs.length; i++) {
    if(cbs[i].type == "checkbox") {
      cbs[i].checked = bx.checked;
    }
  }
}
