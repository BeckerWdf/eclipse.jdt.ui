/*******************************************************************************
 * Copyright (c) 2000, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.text.java.hover;

import java.io.IOException;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.source.ISourceViewer;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.part.IWorkbenchPartOrientation;

import org.eclipse.ui.editors.text.EditorsUI;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SwitchStatement;

import org.eclipse.jdt.internal.corext.codemanipulation.StubUtility;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.util.Messages;
import org.eclipse.jdt.internal.corext.util.Strings;

import org.eclipse.jdt.ui.SharedASTProvider;

import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.javaeditor.JavaEditor;
import org.eclipse.jdt.internal.ui.text.JavaCodeReader;
import org.eclipse.jdt.internal.ui.text.JavaPairMatcher;


/**
 * Provides source as hover info for Java elements.
 */
public class JavaSourceHover extends AbstractJavaEditorTextHover {

	/**
	 * The upward shift in location in lines for the bracket hover.
	 * 
	 * @since 3.8
	 */
	private int fUpwardShiftInLines;

	/**
	 * The status text for the bracket hover.
	 * 
	 * @since 3.8
	 */
	private String fBracketHoverStatus;

	/*
	 * @see JavaElementHover
	 */
	@Deprecated
	public String getHoverInfo(ITextViewer textViewer, IRegion region) {
		IJavaElement[] result= getJavaElementsAt(textViewer, region);
		ITypeRoot editorInput= getEditorInputJavaElement();

		fUpwardShiftInLines= 0;
		fBracketHoverStatus= null;

		if (result == null || result.length == 0)
			return getBracketHoverInfo(textViewer, region, editorInput);

		if (result.length > 1)
			return null;

		IJavaElement curr= result[0];
		if ((curr instanceof IMember || curr instanceof ILocalVariable || curr instanceof ITypeParameter) && curr instanceof ISourceReference) {
			try {
				String source= ((ISourceReference) curr).getSource();

				String[] sourceLines= getTrimmedSource(source, editorInput);
				if (sourceLines == null)
					return null;

				String delim= StubUtility.getLineDelimiterUsed(curr);
				source= Strings.concatenate(sourceLines, delim);

				return source;
			} catch (JavaModelException ex) {
				//do nothing
			}
		}
		return null;
	}

	private String getBracketHoverInfo(ITextViewer textViewer, IRegion region, ITypeRoot editorInput) {
		IEditorPart editor= getEditor();
		if (!(editor instanceof JavaEditor))
			return null;

		int offset= region.getOffset();
		IDocument document= textViewer.getDocument();
		try {
			char c= document.getChar(offset);
			if (c != '}')
				return null;
			JavaPairMatcher matcher= ((JavaEditor) editor).getBracketMatcher();
			IRegion match= matcher.match(document, offset);
			if (match == null)
				return null;

			int sourceOffset;
			int sourceLength;
			String delim= StubUtility.getLineDelimiterUsed(editorInput);

			CompilationUnit ast= SharedASTProvider.getAST(editorInput, SharedASTProvider.WAIT_NO, null);
			if (ast == null)
				return null;
			ASTNode bracketNode= NodeFinder.perform(ast, match.getOffset(),
					match.getLength());
			if (bracketNode == null)
				return null;
			ASTNode node;
			ASTNode parent= bracketNode.getParent();
			if (bracketNode instanceof Block && !(parent instanceof Block) && !(parent instanceof SwitchStatement)) {
				node= parent;
			} else {
				node= bracketNode;
			}
			int nodeStart;
			int nodeLength;
			if (node instanceof BodyDeclaration) {
				BodyDeclaration declaration= (BodyDeclaration) node;
				Javadoc javadoc= declaration.getJavadoc();
				int lengthOfJavadoc= javadoc == null ? 0 : javadoc.getLength() +
						delim.length();
				nodeStart= node.getStartPosition() + lengthOfJavadoc;
				nodeLength= node.getLength() - lengthOfJavadoc;
			} else {
				nodeStart= node.getStartPosition();
				nodeLength= ASTNodes.getExclusiveEnd(bracketNode) - nodeStart;
			}

			int line1= document.getLineOfOffset(nodeStart);
			sourceOffset= document.getLineOffset(line1);
			int line2= document.getLineOfOffset(nodeStart + nodeLength);
			int hoveredLine= document.getLineOfOffset(offset);
			if (line2 > hoveredLine)
				line2= hoveredLine;

			//check if line1 is visible
			JavaEditor javaEditor= (JavaEditor) editor;
			final ISourceViewer viewer= javaEditor.getViewer();
			final int[] topIndex= new int[1];
			StyledText textWidget= viewer.getTextWidget();
			if (textWidget != null) {
				Display display= textWidget.getDisplay();
				display.syncExec(new Runnable() {
					public void run() {
						topIndex[0]= viewer.getTopIndex();
					}
				});
			}
			
			int topLine= topIndex[0];
			int noOfSourceLines;
			IRegion endLine;
			if (line1 < topLine) {
				//match not visible
				noOfSourceLines= 3;
				int skippedLines= Math.abs(line2 - line1 - noOfSourceLines);
				if (skippedLines == 1) {
					noOfSourceLines++;
					skippedLines= 0;
				}
				endLine= document.getLineInformation(line1 + noOfSourceLines);
				fUpwardShiftInLines= noOfSourceLines;
				if (skippedLines > 0) {
					fBracketHoverStatus= Messages.format(JavaHoverMessages.JavaSourceHover_skippedLines, new Integer(skippedLines));
				}
			} else {
				noOfSourceLines= line2 - line1;
				endLine= document.getLineInformation(line2);
				fUpwardShiftInLines= line2 - line1;
			}
			sourceLength= (endLine.getOffset() + endLine.getLength()) - sourceOffset;
			if (fUpwardShiftInLines == 0)
				return null;

			String source= document.get(sourceOffset, sourceLength);
			String[] sourceLines= getTrimmedSource(source, editorInput);
			if (sourceLines == null)
				return null;
			String[] str= new String[noOfSourceLines];
			System.arraycopy(sourceLines, 0, str, 0, noOfSourceLines);
			source= Strings.concatenate(str, delim);
			return source;
		} catch (BadLocationException e) {
			JavaPlugin.log(e);
			return null;
		}
	}

	/**
	 * Returns the trimmed source lines.
	 * 
	 * @param source the source string, could be <code>null</code>
	 * @param editorInput the editor input
	 * @return the trimmed source lines or <code>null</code>
	 */
	private String[] getTrimmedSource(String source, ITypeRoot editorInput) {
		if (source == null)
			return null;
		source= removeLeadingComments(source);
		String[] sourceLines= Strings.convertIntoLines(source);
		Strings.trimIndentation(sourceLines, editorInput.getJavaProject());
		return sourceLines;
	}

	private String removeLeadingComments(String source) {
		final JavaCodeReader reader= new JavaCodeReader();
		IDocument document= new Document(source);
		int i;
		try {
			reader.configureForwardReader(document, 0, document.getLength(), true, false);
			int c= reader.read();
			while (c != -1 && (c == '\r' || c == '\n')) {
				c= reader.read();
			}
			i= reader.getOffset();
			reader.close();
		} catch (IOException ex) {
			i= 0;
		} finally {
			try {
				reader.close();
			} catch (IOException ex) {
				JavaPlugin.log(ex);
			}
		}

		if (i < 0)
			return source;
		return source.substring(i);
	}

	/*
	 * @see org.eclipse.jface.text.ITextHoverExtension#getHoverControlCreator()
	 * @since 3.0
	 */
	@Override
	public IInformationControlCreator getHoverControlCreator() {
		if (fUpwardShiftInLines > 0)
			return createInformationControlCreator(false, fBracketHoverStatus, true);
		else
			return createInformationControlCreator(false, EditorsUI.getTooltipAffordanceString(), true);
	}

	/*
	 * @see org.eclipse.jdt.internal.ui.text.java.hover.AbstractJavaEditorTextHover#getInformationPresenterControlCreator()
	 * @since 3.0
	 */
	@Override
	public IInformationControlCreator getInformationPresenterControlCreator() {
		if (fUpwardShiftInLines > 0)
			return createInformationControlCreator(false, fBracketHoverStatus, true);
		else
			return createInformationControlCreator(true, EditorsUI.getTooltipAffordanceString(), true);
	}

	/**
	 * Returns the information control creator.
	 * 
	 * @param isResizable <code>true</code> if resizable
	 * @param statusFieldText the text to be used in the optional status field or <code>null</code>
	 *            if the status field should be hidden
	 * @param doShiftUp <code>true</code> iff {@link #fUpwardShiftInLines} should be considered
	 * @return the information control creator
	 * @since 3.8
	 */
	private IInformationControlCreator createInformationControlCreator(final boolean isResizable, final String statusFieldText, final boolean doShiftUp) {
		return new IInformationControlCreator() {
			public IInformationControl createInformationControl(final Shell parent) {
				final IEditorPart editor= getEditor();
				int orientation= SWT.NONE;
				if (editor instanceof IWorkbenchPartOrientation)
					orientation= ((IWorkbenchPartOrientation) editor).getOrientation();

				return new SourceViewerInformationControl(parent, isResizable, orientation, statusFieldText) {
					@Override
					public void setLocation(Point location) {
						Point loc= location;
						if (doShiftUp && fUpwardShiftInLines > 0) {
							Point size= super.computeSizeConstraints(0, fUpwardShiftInLines + 1);
							//bracket hover is rendered above '}'
							int y= location.y - size.y - 5; //AbstractInformationControlManager.fMarginY = 5
							Rectangle trim= computeTrim();
							loc= new Point(location.x + trim.x - getViewer().getTextWidget().getLeftMargin(), y - trim.height - trim.y);
						}
						super.setLocation(loc);
					}

					@Override
					public Point computeSizeConstraints(int widthInChars, int heightInChars) {
						if (doShiftUp && fUpwardShiftInLines > 0) {
							Point sizeConstraints= super.computeSizeConstraints(widthInChars, heightInChars);
							return new Point(sizeConstraints.x, 0); //set height as 0 to ensure selection of bottom anchor in AbstractInformationControlManager.computeInformationControlLocation(...)
						} else {
							return super.computeSizeConstraints(widthInChars, heightInChars);
						}
					}

					@Override
					public void setSize(int width, int height) {
						if (doShiftUp && fUpwardShiftInLines != 0) {
							//compute the correct height of hover, this was set to 0 in computeSizeConstraints(..)
							Point size= super.computeSizeConstraints(0, fUpwardShiftInLines);
							Rectangle trim= computeTrim();
							super.setSize(width, size.y + trim.height - trim.y);
						} else {
							super.setSize(width, height);
						}
					}

					@Override
					public IInformationControlCreator getInformationPresenterControlCreator() {
						if (doShiftUp && fUpwardShiftInLines > 0) {
							// Hack: We don't wan't to have auto-enrichment when the mouse moves into the hover,
							// but we do want F2 to persist the hover. The framework has no way to distinguish the
							// two requests, so we have to implement this aspect.
							for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
								if ("canMoveIntoInformationControl".equals(element.getMethodName()) //$NON-NLS-1$
										&& "org.eclipse.jface.text.AbstractHoverInformationControlManager".equals(element.getClassName())) //$NON-NLS-1$
									return null; //do not enrich bracket hover
							}
							return JavaSourceHover.this.createInformationControlCreator(isResizable && !isResizable, statusFieldText, false);
						} else {
							return super.getInformationPresenterControlCreator();
						}
					}
				};
			}
		};
	}
}
