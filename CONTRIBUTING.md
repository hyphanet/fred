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
* Ask the [development mailing list](https://emu.freenetproject.org/cgi-bin/mailman/listinfo/devl)
  or join us in [IRC](https://freenetproject.org/irc.html) - `#freenet` on
  `chat.freenode.net`.

# Code review

Code review helps improve code quality, ensures that multiple people know the
codebase, serves as a defense against introducing malicious code, and makes it
infeasable to pressure people into contributing malicious code. Its goal is
producing software that is readable, correct, and sufficiently efficient.

Breaking API creates an immense amount of work for developers of other projects,
so it is *not* something to be done lightly. If you feel you must make a change
that breaks API and cannot maintain backwards compatibility, please first raise
the issue with the community - the [mailing list](https://emu.freenetproject.org/cgi-bin/mailman/listinfo/devl)
and [IRC](https://freenetproject.org/irc.html) are good places to contact us.

# Standards

Before submitting a pull request, please make sure that modified lines meet the
project [coding standards](https://google-styleguide.googlecode.com/svn/trunk/javaguide.html)
and that the commit messages meet the standards:

## Commit messages

From the git commit man page:

> begin the commit message with a single short (less than 50 character) line
> summarizing the change, followed by a blank line and then a more thorough
> description. The text up to the first blank line in a commit message is
> treated as the commit title...

Except for proper nouns, only the first character of the title may be
capitalized. It may start with the class / module the change applies to,
followed by a colon, especially if it helps to explain the change and give it
context. The main part of it is a short imperative description - a command given
to the codebase. The title is at most 72 characters and does not end with a
period. The second line must be blank for, among other things, git shortlog to
display only the title instead of also including the description. If the message
contains additional prose description on the third and subsequent lines, it is
wrapped to 72 characters. Long lines that do not split well, such as URLs, are
an exception to this.

50 characters on the first line is a soft limit. For more discussion see
https://git.kernel.org/cgit/git/git.git/tree/Documentation/SubmittingPatches#n87

    [Context:] command given to the codebase

For example:

    FProxy: update default bookmark editions
    
    Additional description of what the change does, why it is a good idea,
    and alternate solutions decided against, if any, goes here. For some
    changes this might not be necessary.

Text editors can be configured to assist in formatting messages this way, and
git packages sometimes ship with such configuration.

Each commit in a pull request should represent a logically distinct subset of
the overall change so that it is easy to review. This means the following is
appropriate for things in development:

    Add more tests for new feature
    Fix NPE in new feature
    Fix inverted branch condition in new feature
    Add tests for new feature
    Add exciting new feature

But this is much easier to review:

    Add tests for new feature
    Add exciting new feature

See the Git documentation on [history rewriting](http://git-scm.com/book/en/v2/Git-Tools-Rewriting-History)
for how to do this. `squash` and `fixup` are the relevant actions.
