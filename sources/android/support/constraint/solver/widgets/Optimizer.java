package android.support.constraint.solver.widgets;

import android.support.constraint.solver.LinearSystem;
import android.support.constraint.solver.widgets.ConstraintWidget;

/* loaded from: classes.dex */
public class Optimizer {
    static final int FLAG_CHAIN_DANGLING = 1;
    static final int FLAG_RECOMPUTE_BOUNDS = 2;
    static final int FLAG_USE_OPTIMIZE = 0;
    public static final int OPTIMIZATION_BARRIER = 2;
    public static final int OPTIMIZATION_CHAIN = 4;
    public static final int OPTIMIZATION_DIMENSIONS = 8;
    public static final int OPTIMIZATION_DIRECT = 1;
    public static final int OPTIMIZATION_GROUPS = 32;
    public static final int OPTIMIZATION_NONE = 0;
    public static final int OPTIMIZATION_RATIO = 16;
    public static final int OPTIMIZATION_STANDARD = 7;
    static boolean[] flags = new boolean[3];

    static void checkMatchParent(ConstraintWidgetContainer container, LinearSystem system, ConstraintWidget widget) {
        if (container.mListDimensionBehaviors[0] != ConstraintWidget.DimensionBehaviour.WRAP_CONTENT && widget.mListDimensionBehaviors[0] == ConstraintWidget.DimensionBehaviour.MATCH_PARENT) {
            int left = widget.mLeft.mMargin;
            int right = container.getWidth() - widget.mRight.mMargin;
            widget.mLeft.mSolverVariable = system.createObjectVariable(widget.mLeft);
            widget.mRight.mSolverVariable = system.createObjectVariable(widget.mRight);
            system.addEquality(widget.mLeft.mSolverVariable, left);
            system.addEquality(widget.mRight.mSolverVariable, right);
            widget.mHorizontalResolution = 2;
            widget.setHorizontalDimension(left, right);
        }
        if (container.mListDimensionBehaviors[1] != ConstraintWidget.DimensionBehaviour.WRAP_CONTENT && widget.mListDimensionBehaviors[1] == ConstraintWidget.DimensionBehaviour.MATCH_PARENT) {
            int top = widget.mTop.mMargin;
            int bottom = container.getHeight() - widget.mBottom.mMargin;
            widget.mTop.mSolverVariable = system.createObjectVariable(widget.mTop);
            widget.mBottom.mSolverVariable = system.createObjectVariable(widget.mBottom);
            system.addEquality(widget.mTop.mSolverVariable, top);
            system.addEquality(widget.mBottom.mSolverVariable, bottom);
            if (widget.mBaselineDistance > 0 || widget.getVisibility() == 8) {
                widget.mBaseline.mSolverVariable = system.createObjectVariable(widget.mBaseline);
                system.addEquality(widget.mBaseline.mSolverVariable, widget.mBaselineDistance + top);
            }
            widget.mVerticalResolution = 2;
            widget.setVerticalDimension(top, bottom);
        }
    }

    private static boolean optimizableMatchConstraint(ConstraintWidget constraintWidget, int orientation) {
        if (constraintWidget.mListDimensionBehaviors[orientation] != ConstraintWidget.DimensionBehaviour.MATCH_CONSTRAINT) {
            return false;
        }
        if (constraintWidget.mDimensionRatio != 0.0f) {
            if (constraintWidget.mListDimensionBehaviors[orientation != 0 ? (char) 0 : (char) 1] == ConstraintWidget.DimensionBehaviour.MATCH_CONSTRAINT) {
            }
            return false;
        }
        if (orientation == 0) {
            if (constraintWidget.mMatchConstraintDefaultWidth != 0 || constraintWidget.mMatchConstraintMinWidth != 0 || constraintWidget.mMatchConstraintMaxWidth != 0) {
                return false;
            }
        } else if (constraintWidget.mMatchConstraintDefaultHeight != 0 || constraintWidget.mMatchConstraintMinHeight != 0 || constraintWidget.mMatchConstraintMaxHeight != 0) {
            return false;
        }
        return true;
    }

    static void analyze(int optimisationLevel, ConstraintWidget widget) {
        widget.updateResolutionNodes();
        ResolutionAnchor leftNode = widget.mLeft.getResolutionNode();
        ResolutionAnchor topNode = widget.mTop.getResolutionNode();
        ResolutionAnchor rightNode = widget.mRight.getResolutionNode();
        ResolutionAnchor bottomNode = widget.mBottom.getResolutionNode();
        boolean optimiseDimensions = (optimisationLevel & 8) == 8;
        boolean isOptimizableHorizontalMatch = widget.mListDimensionBehaviors[0] == ConstraintWidget.DimensionBehaviour.MATCH_CONSTRAINT && optimizableMatchConstraint(widget, 0);
        if (leftNode.type != 4 && rightNode.type != 4) {
            if (widget.mListDimensionBehaviors[0] == ConstraintWidget.DimensionBehaviour.FIXED || (isOptimizableHorizontalMatch && widget.getVisibility() == 8)) {
                if (widget.mLeft.mTarget == null && widget.mRight.mTarget == null) {
                    leftNode.setType(1);
                    rightNode.setType(1);
                    if (optimiseDimensions) {
                        rightNode.dependsOn(leftNode, 1, widget.getResolutionWidth());
                    } else {
                        rightNode.dependsOn(leftNode, widget.getWidth());
                    }
                } else if (widget.mLeft.mTarget != null && widget.mRight.mTarget == null) {
                    leftNode.setType(1);
                    rightNode.setType(1);
                    if (optimiseDimensions) {
                        rightNode.dependsOn(leftNode, 1, widget.getResolutionWidth());
                    } else {
                        rightNode.dependsOn(leftNode, widget.getWidth());
                    }
                } else if (widget.mLeft.mTarget == null && widget.mRight.mTarget != null) {
                    leftNode.setType(1);
                    rightNode.setType(1);
                    leftNode.dependsOn(rightNode, -widget.getWidth());
                    if (optimiseDimensions) {
                        leftNode.dependsOn(rightNode, -1, widget.getResolutionWidth());
                    } else {
                        leftNode.dependsOn(rightNode, -widget.getWidth());
                    }
                } else if (widget.mLeft.mTarget != null && widget.mRight.mTarget != null) {
                    leftNode.setType(2);
                    rightNode.setType(2);
                    if (optimiseDimensions) {
                        widget.getResolutionWidth().addDependent(leftNode);
                        widget.getResolutionWidth().addDependent(rightNode);
                        leftNode.setOpposite(rightNode, -1, widget.getResolutionWidth());
                        rightNode.setOpposite(leftNode, 1, widget.getResolutionWidth());
                    } else {
                        leftNode.setOpposite(rightNode, -widget.getWidth());
                        rightNode.setOpposite(leftNode, widget.getWidth());
                    }
                }
            } else if (isOptimizableHorizontalMatch) {
                int width = widget.getWidth();
                leftNode.setType(1);
                rightNode.setType(1);
                if (widget.mLeft.mTarget == null && widget.mRight.mTarget == null) {
                    if (optimiseDimensions) {
                        rightNode.dependsOn(leftNode, 1, widget.getResolutionWidth());
                    } else {
                        rightNode.dependsOn(leftNode, width);
                    }
                } else if (widget.mLeft.mTarget != null && widget.mRight.mTarget == null) {
                    if (optimiseDimensions) {
                        rightNode.dependsOn(leftNode, 1, widget.getResolutionWidth());
                    } else {
                        rightNode.dependsOn(leftNode, width);
                    }
                } else if (widget.mLeft.mTarget == null && widget.mRight.mTarget != null) {
                    if (optimiseDimensions) {
                        leftNode.dependsOn(rightNode, -1, widget.getResolutionWidth());
                    } else {
                        leftNode.dependsOn(rightNode, -width);
                    }
                } else if (widget.mLeft.mTarget != null && widget.mRight.mTarget != null) {
                    if (optimiseDimensions) {
                        widget.getResolutionWidth().addDependent(leftNode);
                        widget.getResolutionWidth().addDependent(rightNode);
                    }
                    if (widget.mDimensionRatio == 0.0f) {
                        leftNode.setType(3);
                        rightNode.setType(3);
                        leftNode.setOpposite(rightNode, 0.0f);
                        rightNode.setOpposite(leftNode, 0.0f);
                    } else {
                        leftNode.setType(2);
                        rightNode.setType(2);
                        leftNode.setOpposite(rightNode, -width);
                        rightNode.setOpposite(leftNode, width);
                        widget.setWidth(width);
                    }
                }
            }
        }
        boolean isOptimizableVerticalMatch = widget.mListDimensionBehaviors[1] == ConstraintWidget.DimensionBehaviour.MATCH_CONSTRAINT && optimizableMatchConstraint(widget, 1);
        if (topNode.type != 4 && bottomNode.type != 4) {
            if (widget.mListDimensionBehaviors[1] == ConstraintWidget.DimensionBehaviour.FIXED || (isOptimizableVerticalMatch && widget.getVisibility() == 8)) {
                if (widget.mTop.mTarget == null && widget.mBottom.mTarget == null) {
                    topNode.setType(1);
                    bottomNode.setType(1);
                    if (optimiseDimensions) {
                        bottomNode.dependsOn(topNode, 1, widget.getResolutionHeight());
                    } else {
                        bottomNode.dependsOn(topNode, widget.getHeight());
                    }
                    if (widget.mBaseline.mTarget != null) {
                        widget.mBaseline.getResolutionNode().setType(1);
                        topNode.dependsOn(1, widget.mBaseline.getResolutionNode(), -widget.mBaselineDistance);
                        return;
                    }
                    return;
                }
                if (widget.mTop.mTarget != null && widget.mBottom.mTarget == null) {
                    topNode.setType(1);
                    bottomNode.setType(1);
                    if (optimiseDimensions) {
                        bottomNode.dependsOn(topNode, 1, widget.getResolutionHeight());
                    } else {
                        bottomNode.dependsOn(topNode, widget.getHeight());
                    }
                    if (widget.mBaselineDistance > 0) {
                        widget.mBaseline.getResolutionNode().dependsOn(1, topNode, widget.mBaselineDistance);
                        return;
                    }
                    return;
                }
                if (widget.mTop.mTarget == null && widget.mBottom.mTarget != null) {
                    topNode.setType(1);
                    bottomNode.setType(1);
                    if (optimiseDimensions) {
                        topNode.dependsOn(bottomNode, -1, widget.getResolutionHeight());
                    } else {
                        topNode.dependsOn(bottomNode, -widget.getHeight());
                    }
                    if (widget.mBaselineDistance > 0) {
                        widget.mBaseline.getResolutionNode().dependsOn(1, topNode, widget.mBaselineDistance);
                        return;
                    }
                    return;
                }
                if (widget.mTop.mTarget != null && widget.mBottom.mTarget != null) {
                    topNode.setType(2);
                    bottomNode.setType(2);
                    if (optimiseDimensions) {
                        topNode.setOpposite(bottomNode, -1, widget.getResolutionHeight());
                        bottomNode.setOpposite(topNode, 1, widget.getResolutionHeight());
                        widget.getResolutionHeight().addDependent(topNode);
                        widget.getResolutionWidth().addDependent(bottomNode);
                    } else {
                        topNode.setOpposite(bottomNode, -widget.getHeight());
                        bottomNode.setOpposite(topNode, widget.getHeight());
                    }
                    if (widget.mBaselineDistance > 0) {
                        widget.mBaseline.getResolutionNode().dependsOn(1, topNode, widget.mBaselineDistance);
                        return;
                    }
                    return;
                }
                return;
            }
            if (isOptimizableVerticalMatch) {
                int height = widget.getHeight();
                topNode.setType(1);
                bottomNode.setType(1);
                if (widget.mTop.mTarget == null && widget.mBottom.mTarget == null) {
                    if (optimiseDimensions) {
                        bottomNode.dependsOn(topNode, 1, widget.getResolutionHeight());
                        return;
                    } else {
                        bottomNode.dependsOn(topNode, height);
                        return;
                    }
                }
                if (widget.mTop.mTarget != null && widget.mBottom.mTarget == null) {
                    if (optimiseDimensions) {
                        bottomNode.dependsOn(topNode, 1, widget.getResolutionHeight());
                        return;
                    } else {
                        bottomNode.dependsOn(topNode, height);
                        return;
                    }
                }
                if (widget.mTop.mTarget == null && widget.mBottom.mTarget != null) {
                    if (optimiseDimensions) {
                        topNode.dependsOn(bottomNode, -1, widget.getResolutionHeight());
                        return;
                    } else {
                        topNode.dependsOn(bottomNode, -height);
                        return;
                    }
                }
                if (widget.mTop.mTarget != null && widget.mBottom.mTarget != null) {
                    if (optimiseDimensions) {
                        widget.getResolutionHeight().addDependent(topNode);
                        widget.getResolutionWidth().addDependent(bottomNode);
                    }
                    if (widget.mDimensionRatio == 0.0f) {
                        topNode.setType(3);
                        bottomNode.setType(3);
                        topNode.setOpposite(bottomNode, 0.0f);
                        bottomNode.setOpposite(topNode, 0.0f);
                        return;
                    }
                    topNode.setType(2);
                    bottomNode.setType(2);
                    topNode.setOpposite(bottomNode, -height);
                    bottomNode.setOpposite(topNode, height);
                    widget.setHeight(height);
                    if (widget.mBaselineDistance > 0) {
                        widget.mBaseline.getResolutionNode().dependsOn(1, topNode, widget.mBaselineDistance);
                    }
                }
            }
        }
    }

    static boolean applyChainOptimized(ConstraintWidgetContainer container, LinearSystem system, int orientation, int offset, ChainHead chainHead) {
        boolean isChainSpread;
        boolean isChainSpreadInside;
        boolean isChainPacked;
        float distance;
        float dimension;
        float dimension2;
        ConstraintWidget next;
        ConstraintWidget first = chainHead.mFirst;
        ConstraintWidget last = chainHead.mLast;
        ConstraintWidget firstVisibleWidget = chainHead.mFirstVisibleWidget;
        ConstraintWidget lastVisibleWidget = chainHead.mLastVisibleWidget;
        ConstraintWidget head = chainHead.mHead;
        ConstraintWidget widget = first;
        boolean done = false;
        int numMatchConstraints = 0;
        float totalWeights = chainHead.mTotalWeight;
        ConstraintWidget constraintWidget = chainHead.mFirstMatchConstraintWidget;
        ConstraintWidget constraintWidget2 = chainHead.mLastMatchConstraintWidget;
        if (container.mListDimensionBehaviors[orientation] == ConstraintWidget.DimensionBehaviour.WRAP_CONTENT) {
        }
        if (orientation == 0) {
            isChainSpread = head.mHorizontalChainStyle == 0;
            isChainSpreadInside = head.mHorizontalChainStyle == 1;
            isChainPacked = head.mHorizontalChainStyle == 2;
        } else {
            isChainSpread = head.mVerticalChainStyle == 0;
            isChainSpreadInside = head.mVerticalChainStyle == 1;
            isChainPacked = head.mVerticalChainStyle == 2;
        }
        float totalSize = 0.0f;
        float totalMargins = 0.0f;
        int numVisibleWidgets = 0;
        while (!done) {
            if (widget.getVisibility() != 8) {
                numVisibleWidgets++;
                if (orientation == 0) {
                    totalSize += widget.getWidth();
                } else {
                    totalSize += widget.getHeight();
                }
                if (widget != firstVisibleWidget) {
                    totalSize += widget.mListAnchors[offset].getMargin();
                }
                if (widget != lastVisibleWidget) {
                    totalSize += widget.mListAnchors[offset + 1].getMargin();
                }
                totalMargins = totalMargins + widget.mListAnchors[offset].getMargin() + widget.mListAnchors[offset + 1].getMargin();
            }
            ConstraintAnchor constraintAnchor = widget.mListAnchors[offset];
            if (widget.getVisibility() != 8 && widget.mListDimensionBehaviors[orientation] == ConstraintWidget.DimensionBehaviour.MATCH_CONSTRAINT) {
                numMatchConstraints++;
                if (orientation == 0) {
                    if (widget.mMatchConstraintDefaultWidth != 0 || widget.mMatchConstraintMinWidth != 0 || widget.mMatchConstraintMaxWidth != 0) {
                        return false;
                    }
                } else if (widget.mMatchConstraintDefaultHeight != 0 || widget.mMatchConstraintMinHeight != 0 || widget.mMatchConstraintMaxHeight != 0) {
                    return false;
                }
                if (widget.mDimensionRatio != 0.0f) {
                    return false;
                }
            }
            ConstraintAnchor nextAnchor = widget.mListAnchors[offset + 1].mTarget;
            if (nextAnchor != null) {
                next = nextAnchor.mOwner;
                if (next.mListAnchors[offset].mTarget == null || next.mListAnchors[offset].mTarget.mOwner != widget) {
                    next = null;
                }
            } else {
                next = null;
            }
            if (next != null) {
                widget = next;
            } else {
                done = true;
            }
        }
        ResolutionAnchor firstNode = first.mListAnchors[offset].getResolutionNode();
        ResolutionAnchor lastNode = last.mListAnchors[offset + 1].getResolutionNode();
        if (firstNode.target == null || lastNode.target == null || firstNode.target.state != 1 || lastNode.target.state != 1) {
            return false;
        }
        if (numMatchConstraints > 0 && numMatchConstraints != numVisibleWidgets) {
            return false;
        }
        float extraMargin = 0.0f;
        if (isChainPacked || isChainSpread || isChainSpreadInside) {
            if (firstVisibleWidget != null) {
                extraMargin = firstVisibleWidget.mListAnchors[offset].getMargin();
            }
            if (lastVisibleWidget != null) {
                extraMargin += lastVisibleWidget.mListAnchors[offset + 1].getMargin();
            }
        }
        float firstOffset = firstNode.target.resolvedOffset;
        float lastOffset = lastNode.target.resolvedOffset;
        if (firstOffset < lastOffset) {
            distance = (lastOffset - firstOffset) - totalSize;
        } else {
            distance = (firstOffset - lastOffset) - totalSize;
        }
        if (numMatchConstraints > 0 && numMatchConstraints == numVisibleWidgets) {
            if (widget.getParent() != null && widget.getParent().mListDimensionBehaviors[orientation] == ConstraintWidget.DimensionBehaviour.WRAP_CONTENT) {
                return false;
            }
            float distance2 = (distance + totalSize) - totalMargins;
            ConstraintWidget widget2 = first;
            float position = firstOffset;
            while (widget2 != null) {
                if (LinearSystem.sMetrics != null) {
                    LinearSystem.sMetrics.nonresolvedWidgets--;
                    LinearSystem.sMetrics.resolvedWidgets++;
                    LinearSystem.sMetrics.chainConnectionResolved++;
                }
                ConstraintWidget next2 = widget2.mNextChainWidget[orientation];
                if (next2 != null || widget2 == last) {
                    float dimension3 = distance2 / numMatchConstraints;
                    if (totalWeights > 0.0f) {
                        if (widget2.mWeight[orientation] == -1.0f) {
                            dimension3 = 0.0f;
                        } else {
                            dimension3 = (widget2.mWeight[orientation] * distance2) / totalWeights;
                        }
                    }
                    if (widget2.getVisibility() == 8) {
                        dimension3 = 0.0f;
                    }
                    float position2 = position + widget2.mListAnchors[offset].getMargin();
                    widget2.mListAnchors[offset].getResolutionNode().resolve(firstNode.resolvedTarget, position2);
                    widget2.mListAnchors[offset + 1].getResolutionNode().resolve(firstNode.resolvedTarget, position2 + dimension3);
                    widget2.mListAnchors[offset].getResolutionNode().addResolvedValue(system);
                    widget2.mListAnchors[offset + 1].getResolutionNode().addResolvedValue(system);
                    position = position2 + dimension3 + widget2.mListAnchors[offset + 1].getMargin();
                }
                widget2 = next2;
            }
            return true;
        }
        if (distance < 0.0f) {
            isChainSpread = false;
            isChainSpreadInside = false;
            isChainPacked = true;
        }
        if (isChainPacked) {
            ConstraintWidget widget3 = first;
            float distance3 = firstOffset + (first.getBiasPercent(orientation) * (distance - extraMargin));
            while (widget3 != null) {
                if (LinearSystem.sMetrics != null) {
                    LinearSystem.sMetrics.nonresolvedWidgets--;
                    LinearSystem.sMetrics.resolvedWidgets++;
                    LinearSystem.sMetrics.chainConnectionResolved++;
                }
                ConstraintWidget next3 = widget3.mNextChainWidget[orientation];
                if (next3 != null || widget3 == last) {
                    if (orientation == 0) {
                        dimension2 = widget3.getWidth();
                    } else {
                        dimension2 = widget3.getHeight();
                    }
                    float distance4 = distance3 + widget3.mListAnchors[offset].getMargin();
                    widget3.mListAnchors[offset].getResolutionNode().resolve(firstNode.resolvedTarget, distance4);
                    widget3.mListAnchors[offset + 1].getResolutionNode().resolve(firstNode.resolvedTarget, distance4 + dimension2);
                    widget3.mListAnchors[offset].getResolutionNode().addResolvedValue(system);
                    widget3.mListAnchors[offset + 1].getResolutionNode().addResolvedValue(system);
                    distance3 = distance4 + dimension2 + widget3.mListAnchors[offset + 1].getMargin();
                }
                widget3 = next3;
            }
        } else if (isChainSpread || isChainSpreadInside) {
            if (isChainSpread) {
                distance -= extraMargin;
            } else if (isChainSpreadInside) {
                distance -= extraMargin;
            }
            ConstraintWidget widget4 = first;
            float gap = distance / (numVisibleWidgets + 1);
            if (isChainSpreadInside) {
                if (numVisibleWidgets > 1) {
                    gap = distance / (numVisibleWidgets - 1);
                } else {
                    gap = distance / 2.0f;
                }
            }
            float distance5 = firstOffset;
            if (first.getVisibility() != 8) {
                distance5 += gap;
            }
            if (isChainSpreadInside && numVisibleWidgets > 1) {
                distance5 = firstOffset + firstVisibleWidget.mListAnchors[offset].getMargin();
            }
            if (isChainSpread && firstVisibleWidget != null) {
                distance5 += firstVisibleWidget.mListAnchors[offset].getMargin();
            }
            while (widget4 != null) {
                if (LinearSystem.sMetrics != null) {
                    LinearSystem.sMetrics.nonresolvedWidgets--;
                    LinearSystem.sMetrics.resolvedWidgets++;
                    LinearSystem.sMetrics.chainConnectionResolved++;
                }
                ConstraintWidget next4 = widget4.mNextChainWidget[orientation];
                if (next4 != null || widget4 == last) {
                    if (orientation == 0) {
                        dimension = widget4.getWidth();
                    } else {
                        dimension = widget4.getHeight();
                    }
                    if (widget4 != firstVisibleWidget) {
                        distance5 += widget4.mListAnchors[offset].getMargin();
                    }
                    widget4.mListAnchors[offset].getResolutionNode().resolve(firstNode.resolvedTarget, distance5);
                    widget4.mListAnchors[offset + 1].getResolutionNode().resolve(firstNode.resolvedTarget, distance5 + dimension);
                    widget4.mListAnchors[offset].getResolutionNode().addResolvedValue(system);
                    widget4.mListAnchors[offset + 1].getResolutionNode().addResolvedValue(system);
                    distance5 += widget4.mListAnchors[offset + 1].getMargin() + dimension;
                    if (next4 != null && next4.getVisibility() != 8) {
                        distance5 += gap;
                    }
                }
                widget4 = next4;
            }
        }
        return true;
    }

    static void setOptimizedWidget(ConstraintWidget widget, int orientation, int resolvedOffset) {
        int startOffset = orientation * 2;
        int endOffset = startOffset + 1;
        widget.mListAnchors[startOffset].getResolutionNode().resolvedTarget = widget.getParent().mLeft.getResolutionNode();
        widget.mListAnchors[startOffset].getResolutionNode().resolvedOffset = resolvedOffset;
        widget.mListAnchors[startOffset].getResolutionNode().state = 1;
        widget.mListAnchors[endOffset].getResolutionNode().resolvedTarget = widget.mListAnchors[startOffset].getResolutionNode();
        widget.mListAnchors[endOffset].getResolutionNode().resolvedOffset = widget.getLength(orientation);
        widget.mListAnchors[endOffset].getResolutionNode().state = 1;
    }
}
