![next branch build status](https://travis-ci.org/freenet/fred.svg?branch=next)

## QUICK START

To install Freenet, use the installer from https://freenetproject.org

If the installer did not do it for you, start Freenet and open a browser pointing
to http://127.0.0.1:8888/

## Contributing

For building Freenet, see README.building.md

Short guidelines for contributing improvements are in CONTRIBUTING.md

## INTRODUCTION

The Freenet Project is very pleased to announce the release of Freenet 0.7.0.

Freenet is software designed to allow the free exchange of information over the 
Internet without fear of censorship, or reprisal. To achieve this Freenet makes it 
very difficult for adversaries to reveal the identity, either of the person 
publishing, or downloading content. The Freenet project started in 1999, released 
Freenet 0.1 in March 2000, and has been under active development ever since.

Freenet is unique in that it handles the storage of content, meaning that if 
necessary users can upload content to Freenet and then disconnect. We've 
discovered that this is a key requirement for many Freenet users. Once uploaded, 
content is mirrored and moved around the Freenet network, making it very difficult 
to trace, or to destroy. Content will remain in Freenet for as long as people are 
retrieving it, although Freenet makes no guarantee that content will be stored 
indefinitely.

The journey towards Freenet 0.7 began in 2005 with the realization that some of 
Freenet's most vulnerable users needed to hide the fact that they were using 
Freenet, not just what they were doing with it. The result of this realization was a 
ground-up redesign and rewrite of Freenet, adding a "darknet" capability, allowing 
users to limit who their Freenet software would communicate with to trusted friends. 
This would make it far more difficult for a third-party to determine who is using 
Freenet.

Freenet 0.7 also embodies significant improvements to almost every other aspect of 
Freenet, including efficiency, security, and usability. Freenet is available for Windows, 
Linux, and OSX. It can be downloaded from:

http://freenetproject.org/download.html

If you have any difficulty getting Freenet to work, or any questions not answered in the 
faq, please join us on IRC in the #freenet channel at irc.freenode.net. Thank you.

This release would not have been possible without the efforts of numerous volunteers, and 
Matthew Toseland, Freenet's full time developer. Matthew's work is funded through donations 
via our website (as well as a few larger sponsors from time to time). We ask that anyone 
who can help us to ensure Matthew's continued employment visit our donations page and 
make a contribution at:

http://freenetproject.org/donate.html

Press enquiries should be directed to Ian Clarke.

## ALWAYS ON
On OSX, Freenet will create a configuration file at 
~/Library/LaunchAgents/com.freenet.startup.plist. On other unix-based systems,
Freenet will create a cron job to run Freenet on startup. On Windows, Freenet is
run by the rabbit tray icon, which starts on login from the startup folder. You should
run Freenet as close to 24x7 as possible for good performance. It is however
possible to remove the plist, to remove the cron job (with the remove cron job
script in bin/), or to remove the startup shortcut (edit the start menu).

## BASIC SECURITY
The easiest option is to use the system tray applet to launch Freenet. This will try to
load a browser (Chrome or Firefox) in privacy/incognito mode. If the browser is in privacy
mode it will tell you. Browser bugs might result in it not being in privacy mode so be
careful of this.

You MUST use a browser in privacy/incognito mode, or ideally use a completely separate
browser to access Freenet. The reason for this is browser history stealing attacks via CSS
which enable malicious websites to probe what other websites - and more importantly
freesites (on-freenet websites) you have been visiting. Many other browser-level attacks
may also be possible, especially if you use a lot of plugins and add-ons, or other
software that is accessed through localhost.

There are many potentially dangerous features in most browsers which you can disable.
Most of them will be turned off by privacy/incognito mode, hopefully. Candidates include
location-based services (geo.enabled) and GoBrowsing (keyword.enabled) in firefox, and 
probably a number of plugins. Note that this is not unique to Firefox - until version 9
or so, Internet Explorer had much worse problems. The most fundamental thing is browser
history probing via CSS. This can be turned off globally and will improve your privacy,
but it is probably still safer to
use a separate browser, and the fact that you have turned it off will likely be
detectable. If you do use a separate browser, you can do some helpful tricks such as
turning off javascript, not loading any addons or plugins, turning off the cache and
history, and setting 127.0.0.1:8888 as a proxy server for all protocols (set it
explicitly for HTTPS even if you set it for all protocols), so that it won't fetch
anything from the web.

Freenet will warn you when you try to download a file which may not be safe. Many file
formats, for instance PDFs, word processor documents, and some types of video, can give
away your identity. In some cases (such as HTML, PNGs, JPEGs and MP3s), Freenet can
automatically make the content safe; a few file formats (such as plain text .txt's) are
safe as-is. Freenet will warn you in all other cases. Sometimes using alternative tools,
or up to date versions of the normal tools, to view such content will help. Another
option is to create a virtual machine with no internet access, create a clean snapshot
having installed the software you need, and then use it to browse the content. Once
finished, reset to the clean snapshot. However, even this is not certain to be
absolutely secure if there is a buffer overflow or similar severe bug: Breaking out of
VMs is not completely unheard of; so you will need to secure the VM, or ideally run it
on a disconnected machine.

You are responsible for your computer's physical security. In many hostile environments
the most likely attack is people busting down your door and stealing your computer -
perhaps because they know you are using Freenet, or perhaps because they got your name
from one of your friends who was also a troublemaker! Freenet can encrypt your caches
and active downloads/uploads, with a password if you set one, and with a panic button to
get rid of the evidence quickly, but as soon as anything is saved to disk, Freenet can't
do anything about it. Freenet can download files to encrypted temporary space ("Fetch"
instead of "Download"), to limit this, but it will use a bit more disk space and is less
convenient. If you can encrypt your whole hard drive, e.g. with Truecrypt, that is
strongly recommended. Even if you can't, we strongly recommend you encrypt your swapfile
(try "fsutil behavior set encryptpagingfile 1" on Windows 7), or turn it off. Note also
that many types of file will be automatically saved to disk by your browser without you
asking it to - mostly these are the kind of files that Freenet will warn you about anyway,
but there might be exceptions depending on what you are using; it's always going to be
safest to encrypt everything.

## MORE SECURITY
If your life or liberty depends on Freenet protecting your anonymity, you should
seriously evaluate your options, including the option of not posting whatever
controversial content it is you are thinking of posting. Freenet has not yet
reached version 1.0, and several important security features have not yet been
implemented; there are several known attacks which future versions will greatly
reduce, and there are likely to be (and have been) serious bugs. If you do 
choose to use Freenet under such circumstances, you  should enable the MAXIMUM 
network security level and add connections to your friends on the Friends page; 
connecting only to friends greatly improves your security, making it very hard to
trace content back to you, and reasonably difficult to find out that you are even
running Freenet, but you should only connect to people you actually know: You are
vulnerable to those nodes you are connected to (hence in low/normal security, aka
opennet mode, you have much less security). Plus, connecting to random strangers
will reduce performance for the network as a whole.

A reasonably detailed explanation of how to use Freenet securely is included in
the first-time wizard, which you see when you first install Freenet, at the bottom
of the page asking whether to connect to strangers or just to friends. Mouse over
it to read it. If you have already installed Freenet you can still see it here:
http://127.0.0.1:8888/wizard/?step=OPENNET

## CHANGES FROM 0.5
This is the 0.7 rewrite of Freenet. This is largely rewritten from scratch, 
although it pulls in a load of code from Dijjer, and most of the crypto and a 
few other classes from the 0.5 source.

### Major changes
- Darknet mode: connect only to your friends, they connect to theirs, this forms
  a small-world network, which Freenet makes routable by location swapping. This
  greatly increases the network's robustness as it makes it much harder to find
  and block Freenet nodes on a national firewall, as well as improving security
  generally provided that your friends are trustworthy. 
- Opennet mode (plug and play) is also supported. Just select network security
  level NORMAL or LOW in the first-time wizard.
- Freenet now uses UDP, mainly to improve connectivity over NATs and firewalls.
- Freenet now uses 32kB fixed block sizes, to improve performance and simplify 
  the code.
- The Freenet Client Protocol is completely different, see the spec here:
  https://wiki.freenetproject.org/FCPv2
- Many more changes...

## LICENSING
Freenet is under the GPL, version 2 or later - see LICENSE.Freenet. We use some
code under the Apache license version 2 (mostly apache commons stuff), and some
modified BSD code (Mantissa). All of which is compatible with the GPL, although
arguably ASL2 is only compatible with GPL3. Some plugins are GPL3.
