![next branch build status](https://travis-ci.org/freenet/fred.svg?branch=next)
![Coverity status](https://scan.coverity.com/projects/2316/badge.svg?flat=1)

## Quickstart

To install Freenet, use the installer from https://freenetproject.org

If the installer did not open a browser with Freenet, start Freenet and access the
Freenet web interface at http://127.0.0.1:8888/

## Contributing

For building Freenet, see README.building.md

Short guidelines for contributing improvements are in CONTRIBUTING.md

## Introduction

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

Also it allows users to let Freenet connect only to friends. This makes it far more 
difficult for a third-party to determine who is using Freenet and as such makes it harder
to punish people just for using Freenet.

Get Freenet via https://freenetproject.org/download.html#autostart

If you have any difficulty getting Freenet to work, or any questions not answered in the 
faq, please join us on IRC in the #freenet channel at irc.freenode.net.

Part of our work is funded through donations 
via our website (as well as a few larger sponsors from time to time). 
If you can, please visit our donations page and make a contribution at:

https://freenetproject.org/donate.html

Press enquiries should be directed at press@freenetproject.org

## Always On
On OSX, Freenet will create a configuration file at 
~/Library/LaunchAgents/com.freenet.startup.plist. On other unix-based systems,
Freenet will create a cron job to run Freenet on startup. On Windows, Freenet is
run by the rabbit tray icon, which starts on login from the startup folder. You should
run Freenet as close to 24x7 as possible for good performance. It is however
possible to remove the plist, to remove the cron job (with the remove cron job
script in bin/), or to remove the startup shortcut (edit the start menu).

## Usage
The easiest option is to use the system tray applet to launch Freenet. This will try to
load a browser (Chrome or Firefox) in privacy/incognito mode. If this does not work,
please enable privacy mode manually.

Privacy mode avoids history probing attacks from regular websites (outside freenet).
Ideally use a completely separate browser to access Freenet.

## Security measures
Freenet will warn you when you try to download a file which may not be safe. Many file
formats, for instance PDFs, word processor documents, and some types of video, can give
away your identity. In some cases (such as HTML, PNGs, JPEGs and MP3s), Freenet can
automatically make the content safe; a few file formats (such as plain text .txt's) are
safe as-is. Freenet will warn you in all other cases. Sometimes using alternative tools,
or up to date versions of the normal tools, to view such content will help. Another
option is to create a virtual machine with no internet access, create a clean snapshot
having installed the software you need, and then use it to browse the content. Once
finished, reset to the clean snapshot. However, even this is not certain to be
absolutely secure: Breaking out of VMs is not completely unheard of; so you will need to
ecure the VM, or ideally run it on a disconnected machine.

You are responsible for your computer's physical security. In many hostile environments
the most likely attack is people busting down your door and stealing your computer -
perhaps because they know you are using Freenet, or perhaps because they got your name
from one of your friends who was busted! Freenet can encrypt your caches
and active downloads/uploads, with a password if you set one, and with a panic button to
get rid of the evidence quickly, but as soon as anything is saved to disk, Freenet can't
do anything about it. Freenet can download files to encrypted temporary space ("Fetch"
instead of "Download"), to limit this, but it will use a bit more disk space and is less
convenient. If you can encrypt your whole hard drive, e.g. with Truecrypt, that is
strongly recommended. Even if you can't, we strongly recommend you encrypt your swapfile
(try "fsutil behavior set encryptpagingfile 1" on Windows 7), or turn off swap.

If your life or liberty depends on Freenet protecting your anonymity, you should
seriously evaluate your options, including the option of not posting whatever
controversial content it is you are thinking of posting. If you do 
choose to use Freenet under such circumstances, you  should enable the MAXIMUM 
network security level and add connections to your friends on the Friends page; 
connecting only to friends greatly improves your security, making it very hard to
trace content back to you, and reasonably difficult to find out that you are
running Freenet, but you should only connect to people you actually know: You are
vulnerable to those nodes you are connected to (hence in low/normal security, aka
opennet mode, you have much less security).

A reasonably detailed explanation of how to use Freenet securely is included in
the first-time wizard, which you see when you first install Freenet, at the bottom
of the page asking whether to connect to strangers or just to friends. Mouse over
it to read it. If you have already installed Freenet you can still see it here:
http://127.0.0.1:8888/wizard/?step=OPENNET

## Licensing
Freenet is under the GPL, version 2 or later - see LICENSE.Freenet. We use some
code under the Apache license version 2 (mostly apache commons stuff), and some
modified BSD code (Mantissa). All of which is compatible with the GPL, although
arguably ASL2 is only compatible with GPL3. Some plugins are GPL3.
