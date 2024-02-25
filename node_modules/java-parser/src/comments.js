import findLast from "lodash/findLast.js";

/**
 * Search where is the position of the comment in the token array by
 * using dichotomic search.
 * @param {*} tokens ordered array of tokens
 * @param {*} comment comment token
 * @return the position of the token next to the comment
 */
function findUpperBoundToken(tokens, comment) {
  let diff;
  let i;
  let current;

  let len = tokens.length;
  i = 0;

  while (len) {
    diff = len >>> 1;
    current = i + diff;
    if (tokens[current].startOffset > comment.startOffset) {
      len = diff;
    } else {
      i = current + 1;
      len -= diff + 1;
    }
  }
  return i;
}

function isPrettierIgnoreComment(comment) {
  return comment.image.match(
    /(\/\/(\s*)prettier-ignore(\s*))|(\/\*(\s*)prettier-ignore(\s*)\*\/)/gm
  );
}

function isFormatterOffOnComment(comment) {
  return comment.image.match(
    /(\/\/(\s*)@formatter:(off|on)(\s*))|(\/\*(\s*)@formatter:(off|on)(\s*)\*\/)/gm
  );
}

/**
 * Pre-processing of tokens in order to
 * complete the parser's mostEnclosiveCstNodeByStartOffset and mostEnclosiveCstNodeByEndOffset structures.
 *
 * @param {ITokens[]} tokens - array of tokens
 * @param {{[startOffset: number]: CSTNode}} mostEnclosiveCstNodeByStartOffset
 * @param {{[endOffset: number]: CSTNode}} mostEnclosiveCstNodeByEndOffset
 */
function completeMostEnclosiveCSTNodeByOffset(
  tokens,
  mostEnclosiveCstNodeByStartOffset,
  mostEnclosiveCstNodeByEndOffset
) {
  tokens.forEach(token => {
    if (mostEnclosiveCstNodeByStartOffset[token.startOffset] === undefined) {
      mostEnclosiveCstNodeByStartOffset[token.startOffset] = token;
    }

    if (mostEnclosiveCstNodeByEndOffset[token.endOffset] === undefined) {
      mostEnclosiveCstNodeByEndOffset[token.endOffset] = token;
    }
  });
}

function extendRangeOffset(comments, tokens) {
  let position;
  comments.forEach(comment => {
    position = findUpperBoundToken(tokens, comment);

    const extendedStartOffset =
      position - 1 < 0 ? comment.startOffset : tokens[position - 1].endOffset;
    const extendedEndOffset =
      position == tokens.length
        ? comment.endOffset
        : tokens[position].startOffset;
    comment.extendedOffset = {
      startOffset: extendedStartOffset,
      endOffset: extendedEndOffset
    };
  });
}

/**
 * Create two data structures we use to know at which offset a comment can be attached.
 * - commentsByExtendedStartOffset: map a comment by the endOffset of the previous token.
 * - commentsByExtendedEndOffset: map a comment by the startOffset of the next token
 *
 * @param {ITokens[]} tokens - array of tokens
 *
 * @return {{commentsByExtendedStartOffset: {[extendedStartOffset: number]: Comment[]}, commentsByExtendedEndOffset: {[extendedEndOffset: number]: Comment[]}}}
 */
function mapCommentsByExtendedRange(comments) {
  const commentsByExtendedEndOffset = {};
  const commentsByExtendedStartOffset = {};

  comments.forEach(comment => {
    const extendedStartOffset = comment.extendedOffset.startOffset;
    const extendedEndOffset = comment.extendedOffset.endOffset;

    if (commentsByExtendedEndOffset[extendedEndOffset] === undefined) {
      commentsByExtendedEndOffset[extendedEndOffset] = [comment];
    } else {
      commentsByExtendedEndOffset[extendedEndOffset].push(comment);
    }

    if (commentsByExtendedStartOffset[extendedStartOffset] === undefined) {
      commentsByExtendedStartOffset[extendedStartOffset] = [comment];
    } else {
      commentsByExtendedStartOffset[extendedStartOffset].push(comment);
    }
  });

  return { commentsByExtendedEndOffset, commentsByExtendedStartOffset };
}

/**
 * Determine if a comment should be attached as a trailing comment to a specific node.
 * A comment should be trailing if it is on the same line than the previous token and
 * not on the same line than the next token
 *
 * @param {*} comment
 * @param {CSTNode} node
 * @param {{[startOffset: number]: CSTNode}} mostEnclosiveCstNodeByStartOffset
 */
function shouldAttachTrailingComments(
  comment,
  node,
  mostEnclosiveCstNodeByStartOffset
) {
  if (isPrettierIgnoreComment(comment)) {
    return false;
  }

  const nextNode =
    mostEnclosiveCstNodeByStartOffset[comment.extendedOffset.endOffset];

  // Last node of the file
  if (nextNode === undefined) {
    return true;
  }

  const nodeEndLine =
    node.location !== undefined ? node.location.endLine : node.endLine;

  if (comment.startLine !== nodeEndLine) {
    return false;
  }

  const nextNodeStartLine =
    nextNode.location !== undefined
      ? nextNode.location.startLine
      : nextNode.startLine;
  return comment.endLine !== nextNodeStartLine;
}

/**
 * Attach comments to the most enclosive CSTNode (node or token)
 *
 * @param {ITokens[]} tokens
 * @param {*} comments
 * @param {{[startOffset: number]: CSTNode}} mostEnclosiveCstNodeByStartOffset
 * @param {{[endOffset: number]: CSTNode}} mostEnclosiveCstNodeByEndOffset
 */
export function attachComments(
  tokens,
  comments,
  mostEnclosiveCstNodeByStartOffset,
  mostEnclosiveCstNodeByEndOffset
) {
  // Edge case: only comments in the file
  if (tokens.length === 0) {
    mostEnclosiveCstNodeByStartOffset[NaN].leadingComments = comments;
    return;
  }

  // Pre-processing phase to complete the data structures we need to attach
  // a comment to the right place
  completeMostEnclosiveCSTNodeByOffset(
    tokens,
    mostEnclosiveCstNodeByStartOffset,
    mostEnclosiveCstNodeByEndOffset
  );

  extendRangeOffset(comments, tokens);
  const { commentsByExtendedStartOffset, commentsByExtendedEndOffset } =
    mapCommentsByExtendedRange(comments);

  /*
    This set is here to ensure that we attach comments only once
    If a comment is attached to a node or token, we remove it from this set
  */
  const commentsToAttach = new Set(comments);

  // Attach comments as trailing comments if desirable
  Object.keys(mostEnclosiveCstNodeByEndOffset).forEach(endOffset => {
    // We look if some comments is directly following this node/token
    if (commentsByExtendedStartOffset[endOffset] !== undefined) {
      const nodeTrailingComments = commentsByExtendedStartOffset[
        endOffset
      ].filter(comment => {
        return (
          shouldAttachTrailingComments(
            comment,
            mostEnclosiveCstNodeByEndOffset[endOffset],
            mostEnclosiveCstNodeByStartOffset
          ) && commentsToAttach.has(comment)
        );
      });

      if (nodeTrailingComments.length > 0) {
        mostEnclosiveCstNodeByEndOffset[endOffset].trailingComments =
          nodeTrailingComments;
      }

      nodeTrailingComments.forEach(comment => {
        commentsToAttach.delete(comment);
      });
    }
  });

  // Attach rest of comments as leading comments
  Object.keys(mostEnclosiveCstNodeByStartOffset).forEach(startOffset => {
    // We look if some comments is directly preceding this node/token
    if (commentsByExtendedEndOffset[startOffset] !== undefined) {
      const nodeLeadingComments = commentsByExtendedEndOffset[
        startOffset
      ].filter(comment => commentsToAttach.has(comment));

      if (nodeLeadingComments.length > 0) {
        mostEnclosiveCstNodeByStartOffset[startOffset].leadingComments =
          nodeLeadingComments;
      }

      // prettier ignore support
      for (let i = 0; i < nodeLeadingComments.length; i++) {
        if (isPrettierIgnoreComment(nodeLeadingComments[i])) {
          const node = mostEnclosiveCstNodeByStartOffset[startOffset];
          const ignoreNode =
            node.name === "blockStatements"
              ? node.children.blockStatement[0]
              : node;
          ignoreNode.ignore = true;
          break;
        }
      }
    }
  });
}

/**
 * Create pairs of formatter:off and formatter:on
 * @param comments
 * @returns pairs of formatter:off and formatter:on
 */
export function matchFormatterOffOnPairs(comments) {
  const onOffComments = comments.filter(comment =>
    isFormatterOffOnComment(comment)
  );

  let isPreviousCommentOff = false;
  let isCurrentCommentOff = true;
  const pairs = [];
  let paired = {};
  onOffComments.forEach(comment => {
    isCurrentCommentOff = comment.image.slice(-3) === "off";

    if (!isPreviousCommentOff) {
      if (isCurrentCommentOff) {
        paired.off = comment;
      }
    } else {
      if (!isCurrentCommentOff) {
        paired.on = comment;
        pairs.push(paired);
        paired = {};
      }
    }
    isPreviousCommentOff = isCurrentCommentOff;
  });

  if (onOffComments.length > 0 && isCurrentCommentOff) {
    paired.on = undefined;
    pairs.push(paired);
  }

  return pairs;
}

/**
 * Check if the node is between formatter:off and formatter:on and change his ignore state
 * @param node
 * @param commentPairs
 */
export function shouldNotFormat(node, commentPairs) {
  const matchingPair = findLast(
    commentPairs,
    comment => comment.off.endOffset < node.location.startOffset
  );
  if (
    matchingPair !== undefined &&
    (matchingPair.on === undefined ||
      matchingPair.on.startOffset > node.location.endOffset)
  ) {
    node.ignore = true;
  }
}
