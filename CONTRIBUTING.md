# Welcome!
This document assumes you have already [set up a development
environment](https://wiki.freenetproject.org/Building_from_source) and have made
a change you want to submit for review. If you'd like an idea for something to
start with:

* Is there anything you've found annoying or lacking while using Freenet that
  you'd like to fix? (There might even be a bug filed already.)
* The [bug tracker](https://bugs.freenetproject.org/my_view_page.php) has tasks.
  List only bugs filed against this repository by selecting "Freenet" in the
  "Project" drop-down in the upper right. Do any of these look interesting?
* Check the [projects](https://wiki.freenetproject.org/Projects) page.
* Join us in [IRC](https://web.libera.chat/?nick=Rabbit|?#freenet) - `#freenet` on
  `irc.libera.chat` or in FMS.

# Code review

Once you have a change ready for review, please submit it as a [pull
request](https://help.github.com/articles/using-pull-requests/#initiating-the-pull-request)
against the `next` branch. It's usually a good idea to work on a topic / feature
branch that starts at `next` and is specific to the pull request. Once you have
submitted a pull request, people will start reviewing it and providing feedback.
If you don't hear anything for a while feel free to bring it to the attention of
people in [IRC](https://www.hyphanet.org/pages/help.html#chat-with-us) or FMS - we may
have missed it.

All commits with functional changes must be reviewed, including those from
maintainers. An exception are plugins: updates to official plugins
must include the commit id from the the plugin-repo in the commit
description; the review in the plugin repository then serves as review in fred.

Code review helps improve code quality, ensures that multiple people know the
codebase, serves as a defense against introducing malicious code, and makes it
infeasible to pressure people into contributing malicious code. Its goal is
producing software that is readable, correct, and sufficiently efficient.

# Expensive changes

Breaking API creates an immense amount of work for developers of other projects,
so it is *not* something to be done lightly. If you feel you must make a change
that breaks API and cannot maintain backwards compatibility, please first raise
the issue with the community - FMS
and [IRC](https://www.hyphanet.org/pages/help.html#chat-with-us) are good places to contact us.

Changing dependencies must be accompanied with changes in every
installer and updates to `dependencies.properties` and existing
`wrapper.conf` files of every node. If you need this, you have to do
and test all those related changes, too.

# Standards

Before submitting a pull request, please:

* add an entry to the [NEWS](/NEWS.md) file if appropriate.
* ensure modified lines meet the project [coding standards](https://google.github.io/styleguide/javaguide.html).
* ensure that your pull request is easy to work with. File additional pull-requests for infrastructure if those are required to release your change.
* ensure the commit messages meet the standards:

## Commit messages

The first line is the title:

* Write it as a command to the codebase
* Limit the length to 72 characters
* Do not put a period at the end
* (optional) Separate context with a colon

The second line is blank. If the message contains additional prose description
on the third and subsequent lines, it is wrapped to 72 characters. Long lines
that do not split well, such as URLs or stack traces, are an exception to this.

For example:

    Update default bookmark editions
    
    Additional description of what the change does, why it is a good idea,
    and any alternate solutions that seem more obvious and were decided
    against goes here. For some changes this might not be necessary.

For more discussion see the [git patch submission documentation](https://git.kernel.org/cgit/git/git.git/tree/Documentation/SubmittingPatches#n87).

Text editors can be configured to assist in formatting messages this way, and
git packages sometimes ship with such configuration.

