document.getElementById('loaderScript').parentNode.removeChild(document.getElementById('loaderScript'));
var scriptNode=document.createElement('script');
scriptNode.type='text/javascript';
scriptNode.src='/static/freenetjs/freenetjs.nocache.js';
document.getElementsByTagName('body')[0].appendChild(scriptNode);