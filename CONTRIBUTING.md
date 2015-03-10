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
* Ask the [development mailing list](https://emu.freenetproject.org/cgi-bin/mailman/listinfo/devl)
  or join us in [IRC](https://freenetproject.org/irc.html) - `#freenet` on
  `chat.freenode.net`.

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

Except for proper nouns, only the first character of the first line may be
capitalized. It can start with the class / module the change applies to,
followed by a colon, especially if it helps to explain the change and give it
context. The main part of it is a short imperative description that is at most
72 characters and does not end with a full stop. The second line must be blank
for, among other things, git shortlog to display only the title instead of also
including in the description. If the message contains additional prose
description on the third and subsequent lines, it is wrapped to 72 characters.
Long lines that do not split well, such as URLs, are an exception to this.

50 characters on the first line is a soft limit. For more discussion see
https://git.kernel.org/cgit/git/git.git/tree/Documentation/SubmittingPatches#n87

    [Context:] command given to the codebase

For example:

    FProxy: update default bookmark editions
    
    Additional description of what the change does, why it is a good idea,
    and alternate solutions decided against, if any, goes here. For some
    changes this might not be necessary.

or

    Reduce hydrocoptic marzul vane side fumbling
    
    This change is such that starting the summary with an area of change
    would not be helpful. The existing marzul vane fittings are prone to
    side fumbling at sinusoidal depleneration duty cycles over 80%. This
    hyperfastens the vanes to avoid unilateral boloid shedding which
    contributes to the side fumbling. Non-reversible tremmy pipes would
    have had similar effects but added more weight to the casing.

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

# Code review

Code review reviews code and commits, not their authors. Its goal is producing
code that is readable, correct, and sufficiently efficient. Except for
confusingly inefficient code - so much that it impacts readability -
optimization is to be guided by benchmarks and not what feels faster.

Breaking API creates an immense amount of work for developers of other projects,
so it is *not* something to be done lightly. If you feel you must make a change
that breaks API and cannot maintain backwards compatibility, please first raise
the issue with the community - the [mailing list](https://emu.freenetproject.org/cgi-bin/mailman/listinfo/devl)
and [IRC](https://freenetproject.org/irc.html) are good places to contact us.
