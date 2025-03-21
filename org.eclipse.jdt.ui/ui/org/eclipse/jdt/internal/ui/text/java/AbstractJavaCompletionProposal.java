/*******************************************************************************
 * Copyright (c) 2005, 2020 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Christian Georgi<christian.georgi@sap.com> - Bug 462770: Use OS symbol for 'Ctrl'
 *     Gábor Kövesdán - Contribution for Bug 350000 - [content assist] Include non-prefix matches in auto-complete suggestions
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.text.java;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

import org.osgi.framework.Bundle;

import org.eclipse.osgi.util.TextProcessor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Shell;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

import org.eclipse.jface.internal.text.html.BrowserInformationControl;
import org.eclipse.jface.internal.text.html.HTMLPrinter;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.StyledString;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.BadPositionCategoryException;
import org.eclipse.jface.text.DefaultPositionUpdater;
import org.eclipse.jface.text.DocumentCommand;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IPositionUpdater;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.ITextViewerExtension4;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.contentassist.BoldStylerProvider;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension4;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension7;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.link.ILinkedModeListener;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.jface.text.link.LinkedModeUI;
import org.eclipse.jface.text.link.LinkedModeUI.ExitFlags;
import org.eclipse.jface.text.link.LinkedModeUI.IExitPolicy;
import org.eclipse.jface.text.link.LinkedPosition;
import org.eclipse.jface.text.link.LinkedPositionGroup;

import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchSite;

import org.eclipse.ui.texteditor.link.EditorLinkedModeUI;

import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.search.SearchPattern;

import org.eclipse.jdt.internal.core.manipulation.JavaManipulationPlugin;
import org.eclipse.jdt.internal.corext.javadoc.JavaDocLocations;
import org.eclipse.jdt.internal.corext.util.Strings;

import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.text.IJavaPartitions;
import org.eclipse.jdt.ui.text.JavaTextTools;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;

import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.text.java.hover.JavadocBrowserInformationControlInput;
import org.eclipse.jdt.internal.ui.text.java.hover.JavadocHover;
import org.eclipse.jdt.internal.ui.text.javadoc.JavadocContentAccess2;


/**
 *
 * @since 3.2
 */
public abstract class AbstractJavaCompletionProposal implements IJavaCompletionProposal, ICompletionProposalExtension, ICompletionProposalExtension2, ICompletionProposalExtension3,
	ICompletionProposalExtension4, ICompletionProposalExtension5, ICompletionProposalExtension6, ICompletionProposalExtension7 {


	/**
	 * The key modifier that toggles whether to insert or overwrite.
	 */
	public static final int MODIFIER_TOGGLE_COMPLETION_MODE= SWT.CTRL;

	/**
	 * A class to simplify tracking a reference position in a document.
	 */
	static final class ReferenceTracker {

		/** The reference position category name. */
		private static final String CATEGORY= "reference_position"; //$NON-NLS-1$
		/** The position updater of the reference position. */
		private final IPositionUpdater fPositionUpdater= new DefaultPositionUpdater(CATEGORY);
		/** The reference position. */
		private final Position fPosition= new Position(0);

		/**
		 * Called before document changes occur. It must be followed by a call to postReplace().
		 *
		 * @param document the document on which to track the reference position.
		 * @param offset the offset
		 * @throws BadLocationException if the offset describes an invalid range in this document
		 *
		 */
		public void preReplace(IDocument document, int offset) throws BadLocationException {
			fPosition.setOffset(offset);
			try {
				document.addPositionCategory(CATEGORY);
				document.addPositionUpdater(fPositionUpdater);
				document.addPosition(CATEGORY, fPosition);

			} catch (BadPositionCategoryException e) {
				// should not happen
				JavaPlugin.log(e);
			}
		}

		/**
		 * Called after the document changed occurred. It must be preceded by a call to preReplace().
		 *
		 * @param document the document on which to track the reference position.
		 * @return offset after the replace
		 */
		public int postReplace(IDocument document) {
			try {
				document.removePosition(CATEGORY, fPosition);
				document.removePositionUpdater(fPositionUpdater);
				document.removePositionCategory(CATEGORY);

			} catch (BadPositionCategoryException e) {
				// should not happen
				JavaPlugin.log(e);
			}
			return fPosition.getOffset();
		}
	}

	protected static class ExitPolicy implements IExitPolicy {

		final char fExitCharacter;
		private final IDocument fDocument;

		public ExitPolicy(char exitCharacter, IDocument document) {
			fExitCharacter= exitCharacter;
			fDocument= document;
		}

		@Override
		public ExitFlags doExit(LinkedModeModel environment, VerifyEvent event, int offset, int length) {

			if (event.character == fExitCharacter) {
				if (environment.anyPositionContains(offset))
					return new ExitFlags(ILinkedModeListener.UPDATE_CARET, false);
				else
					return new ExitFlags(ILinkedModeListener.UPDATE_CARET, true);
			}

			switch (event.character) {
				case ';':
					return new ExitFlags(ILinkedModeListener.NONE, true);
				case SWT.CR:
					// 1) when entering an anonymous class as a parameter, we don't want
					// to jump after the parenthesis when return is pressed
					// 2) after auto completion of methods without parameters, exit from linked mode when return is pressed
					if (offset > 0) {
						try {
							char prevOffsetChar= fDocument.getChar(offset - 1);
							if (prevOffsetChar == '{' || prevOffsetChar == ';')
								return new ExitFlags(ILinkedModeListener.EXIT_ALL, true);
						} catch (BadLocationException e) {
						}
					}
					return null;
				default:
					return null;
			}
		}

	}

	private StyledString fDisplayString;
	private String fReplacementString;
	private int fReplacementOffset;
	private int fReplacementLength;
	private int fCursorPosition;
	private Image fImage;
	private IContextInformation fContextInformation;
	private ProposalInfo fProposalInfo;
	private char[] fTriggerCharacters;
	private String fSortString;
	private int fRelevance;
	private boolean fIsInJavadoc;

	private int fPatternMatchRule= -1;

	private StyleRange fRememberedStyleRange;

	private boolean fToggleEating;
	private ITextViewer fTextViewer;


	/**
	 * The control creator.
	 *
	 * @since 3.2
	 */
	private IInformationControlCreator fCreator;
	/**
	 * The CSS used to format javadoc information.
	 * @since 3.3
	 */
	private static String fgCSSStyles;

	/**
	 * The invocation context of this completion proposal. Can be <code>null</code>.
	 */
	protected final JavaContentAssistInvocationContext fInvocationContext;

	/**
	 * Cache to store last validation state.
	 * @since 3.5
	 */
	private boolean fIsValidated= true;

	/**
	 * The text presentation listener.
	 * @since 3.6
	 */
	private ITextPresentationListener fTextPresentationListener;

	protected AbstractJavaCompletionProposal() {
		fInvocationContext= null;
	}

	protected AbstractJavaCompletionProposal(JavaContentAssistInvocationContext context) {
		fInvocationContext= context;
	}

	@Override
	public char[] getTriggerCharacters() {
		return fTriggerCharacters;
	}

	/**
	 * Sets the trigger characters.
	 *
	 * @param triggerCharacters The set of characters which can trigger the application of this
	 *        completion proposal
	 */
	public void setTriggerCharacters(char[] triggerCharacters) {
		fTriggerCharacters= triggerCharacters;
	}

	/**
	 * Sets the proposal info.
	 *
	 * @param proposalInfo The additional information associated with this proposal or
	 *        <code>null</code>
	 */
	public void setProposalInfo(ProposalInfo proposalInfo) {
		fProposalInfo= proposalInfo;
	}

	/**
	 * Returns the additional proposal info, or <code>null</code> if none exists.
	 *
	 * @return the additional proposal info, or <code>null</code> if none exists
	 */
	protected ProposalInfo getProposalInfo() {
		return fProposalInfo;
	}

	/**
	 * Sets the cursor position relative to the insertion offset. By default this is the length of
	 * the completion string (Cursor positioned after the completion)
	 *
	 * @param cursorPosition The cursorPosition to set
	 */
	public void setCursorPosition(int cursorPosition) {
		Assert.isTrue(cursorPosition >= 0);
		fCursorPosition= cursorPosition;
	}

	protected int getCursorPosition() {
		return fCursorPosition;
	}

	@Override
	public final void apply(IDocument document) {
		// not used any longer
		apply(document, (char) 0, getReplacementOffset() + getReplacementLength());
	}

	@Override
	public void apply(IDocument document, char trigger, int offset) {

		if (isSupportingRequiredProposals()) {
			CompletionProposal coreProposal= ((MemberProposalInfo)getProposalInfo()).fProposal;
			CompletionProposal[] requiredProposals= coreProposal.getRequiredProposals();
			for (int i= 0; requiredProposals != null &&  i < requiredProposals.length; i++) {
				int oldLen= document.getLength();
				if (requiredProposals[i].getKind() == CompletionProposal.TYPE_REF) {
					LazyJavaCompletionProposal proposal= createRequiredTypeCompletionProposal(requiredProposals[i], fInvocationContext);
					proposal.apply(document);
					setReplacementOffset(getReplacementOffset() + document.getLength() - oldLen);
				} else if (requiredProposals[i].getKind() == CompletionProposal.TYPE_IMPORT
						|| requiredProposals[i].getKind() == CompletionProposal.METHOD_IMPORT
						|| requiredProposals[i].getKind() == CompletionProposal.FIELD_IMPORT) {
					ImportCompletionProposal proposal= new ImportCompletionProposal(requiredProposals[i], fInvocationContext, coreProposal.getKind());
					proposal.setReplacementOffset(getReplacementOffset());
					proposal.apply(document);
					setReplacementOffset(getReplacementOffset() + document.getLength() - oldLen - proposal.getLengthOfImportsAddedBehindReplacementOffset());
				} else {
					/*
					 * In 3.3 we only support the above required proposals, see
					 * CompletionProposal#getRequiredProposals()
					 */
					 Assert.isTrue(false);
				}
			}
		}

		try {
			boolean isSmartTrigger= isSmartTrigger(trigger);

			String replacement;
			if (isSmartTrigger || trigger == (char) 0) {
				int referenceOffset= getReplacementOffset() + getReplacementLength();
				replacement= getReplacementString();
				//add ; to the replacement string if replacement string do not end with a semicolon and the document do not already have a ; at the reference offset.
				if (trigger == ';' && replacement.charAt(replacement.length() - 1) != ';' && (referenceOffset >= document.getLength() || document.getChar(referenceOffset) != ';')) {
					replacement= replacement + ";"; //$NON-NLS-1$
					setReplacementString(replacement);
				}
			} else {
				StringBuilder buffer= new StringBuilder(getReplacementString());

				// fix for PR #5533. Assumes that no eating takes place.
				if ((getCursorPosition() > 0 && getCursorPosition() <= buffer.length() && buffer.charAt(getCursorPosition() - 1) != trigger)) {
					// insert trigger ';' for methods with parameter at the end of the replacement string and not at the cursor position.
					int length= getReplacementString().length();
					if (trigger == ';' && getCursorPosition() != length) {
						if (buffer.charAt(length - 1) != trigger) {
							buffer.insert(length, trigger);
						}
					} else {
						buffer.insert(getCursorPosition(), trigger);
						setCursorPosition(getCursorPosition() + 1);
					}
				}

				replacement= buffer.toString();
				setReplacementString(replacement);
			}

			// reference position just at the end of the document change.
			int referenceOffset= getReplacementOffset() + getReplacementLength();
			final ReferenceTracker referenceTracker= new ReferenceTracker();
			referenceTracker.preReplace(document, referenceOffset);

			replace(document, getReplacementOffset(), getReplacementLength(), replacement);

			referenceOffset= referenceTracker.postReplace(document);
			setReplacementOffset(referenceOffset - (replacement == null ? 0 : replacement.length()));

			// PR 47097
			if (isSmartTrigger) {
				// avoid inserting redundant semicolon when smart insert is enabled.
				if ((trigger != ';') || (!replacement.endsWith(";") && (document.getChar(referenceOffset) != ';'))) { //$NON-NLS-1$
					handleSmartTrigger(document, trigger, referenceOffset);
				}
			}

		} catch (BadLocationException x) {
			// ignore
		}
	}


	/**
	 * Creates the required type proposal.
	 *
	 * @param completionProposal the core completion proposal
	 * @param invocationContext invocation context
	 * @return the required type completion proposal
	 * @since 3.5
	 */
	protected LazyJavaCompletionProposal createRequiredTypeCompletionProposal(CompletionProposal completionProposal, JavaContentAssistInvocationContext invocationContext) {
		if (PreferenceConstants.getPreferenceStore().getBoolean(PreferenceConstants.CODEASSIST_FILL_ARGUMENT_NAMES))
			return (LazyJavaCompletionProposal)new FillArgumentNamesCompletionProposalCollector(invocationContext).createJavaCompletionProposal(completionProposal);
		else
			return new LazyJavaTypeCompletionProposal(completionProposal, invocationContext);
	}

	private boolean isSmartTrigger(char trigger) {
		return trigger == ';' && JavaPlugin.getDefault().getCombinedPreferenceStore().getBoolean(PreferenceConstants.EDITOR_SMART_SEMICOLON)
				|| trigger == '{' && JavaPlugin.getDefault().getCombinedPreferenceStore().getBoolean(PreferenceConstants.EDITOR_SMART_OPENING_BRACE);
	}

	private void handleSmartTrigger(IDocument document, char trigger, int referenceOffset) throws BadLocationException {
		DocumentCommand cmd= new DocumentCommand() {
		};

		cmd.offset= referenceOffset;
		cmd.length= 0;
		cmd.text= Character.toString(trigger);
		cmd.doit= true;
		cmd.shiftsCaret= true;
		cmd.caretOffset= getReplacementOffset() + getCursorPosition();

		SmartSemicolonAutoEditStrategy strategy= new SmartSemicolonAutoEditStrategy(IJavaPartitions.JAVA_PARTITIONING);
		strategy.customizeDocumentCommand(document, cmd);

		replace(document, cmd.offset, cmd.length, cmd.text);
		setCursorPosition(cmd.caretOffset - getReplacementOffset() + cmd.text.length());
	}

	protected final void replace(IDocument document, int offset, int length, String string) throws BadLocationException {
		if (!document.get(offset, length).equals(string))
			document.replace(offset, length, string);
	}

	@Override
	public void apply(ITextViewer viewer, char trigger, int stateMask, int offset) {

		IDocument document= viewer.getDocument();
		if (fTextViewer == null)
			fTextViewer= viewer;

		// see https://bugs.eclipse.org/bugs/show_bug.cgi?id=96059
		// don't apply the proposal if for some reason we're not valid any longer
		if (!isInJavadoc() && !validate(document, offset, null)) {
			setCursorPosition(offset);
			if (trigger != '\0') {
				try {
					document.replace(offset, 0, String.valueOf(trigger));
					setCursorPosition(getCursorPosition() + 1);
					if (trigger == '(' && autocloseBrackets()) {
						document.replace(getReplacementOffset() + getCursorPosition(), 0, ")"); //$NON-NLS-1$
						setUpLinkedMode(document, ')');
					}
				} catch (BadLocationException x) {
					// ignore
				}
			}
			return;
		}

		// don't eat if not in preferences, XOR with Ctrl
		// but: if there is a selection, replace it!
		Point selection= viewer.getSelectedRange();
		fToggleEating= (stateMask & MODIFIER_TOGGLE_COMPLETION_MODE) != 0;
		int newLength= selection.x + selection.y - getReplacementOffset();
		if ((insertCompletion() ^ fToggleEating) && newLength >= 0)
			setReplacementLength(newLength);

		apply(document, trigger, offset);
		fToggleEating= false;
	}

	/**
	 * Tells whether the user toggled the insert mode by pressing the 'Ctrl' key.
	 *
	 * @return <code>true</code> if the insert mode is toggled, <code>false</code> otherwise
	 * @since 3.5
	 */
	protected boolean isInsertModeToggled() {
		return fToggleEating;
	}

	/**
	 * Returns <code>true</code> if the proposal is within javadoc, <code>false</code> otherwise.
	 *
	 * @return <code>true</code> if the proposal is within javadoc, <code>false</code> otherwise
	 */
	protected boolean isInJavadoc() {
		return fIsInJavadoc;
	}

	/**
	 * Sets the javadoc attribute.
	 *
	 * @param isInJavadoc <code>true</code> if the proposal is within javadoc
	 */
	protected void setInJavadoc(boolean isInJavadoc) {
		fIsInJavadoc= isInJavadoc;
	}

	@Override
	public Point getSelection(IDocument document) {
		if (!fIsValidated)
			return null;
		return new Point(getReplacementOffset() + getCursorPosition(), 0);
	}

	@Override
	public IContextInformation getContextInformation() {
		return fContextInformation;
	}

	/**
	 * Sets the context information.
	 * @param contextInformation The context information associated with this proposal
	 */
	public void setContextInformation(IContextInformation contextInformation) {
		fContextInformation= contextInformation;
	}

	@Override
	public String getDisplayString() {
		if (fDisplayString != null)
			return fDisplayString.getString();
		return ""; //$NON-NLS-1$
	}

	@Override
	public String getAdditionalProposalInfo() {
		Object info= getAdditionalProposalInfo(new NullProgressMonitor());
		return info == null ? null : info.toString();
	}

	@Override
	public Object getAdditionalProposalInfo(IProgressMonitor monitor) {
		ProposalInfo proposalInfo= getProposalInfo();
		if (proposalInfo != null) {
			try {
				StringBuilder buffer= new StringBuilder();
				IJavaElement element= proposalInfo.getJavaElement();

				if (element != null) {
					String base= null;
					String info= proposalInfo.getInfo(monitor);
					if (info != null && info.length() > 0) {
						buffer.append(info);
						base= JavadocContentAccess2.extractBaseURL(info);
					}
					if (base == null && element instanceof IMember) {
						base= JavaDocLocations.getBaseURL(element, ((IMember) element).isBinary());
					}

					addConstantOrDefaultValue(buffer, element);

					if (buffer.length() > 0) {
						ColorRegistry registry= JFaceResources.getColorRegistry();
						RGB fgRGB= registry.getRGB("org.eclipse.jdt.ui.Javadoc.foregroundColor"); //$NON-NLS-1$
						RGB bgRGB= registry.getRGB("org.eclipse.jdt.ui.Javadoc.backgroundColor"); //$NON-NLS-1$
						HTMLPrinter.insertPageProlog(buffer, 0, fgRGB, bgRGB, getCSSStyles());
						if (base != null) {
							int endHeadIdx= buffer.indexOf("</head>"); //$NON-NLS-1$
							buffer.insert(endHeadIdx, "\n<base href='" + base + "'>\n"); //$NON-NLS-1$ //$NON-NLS-2$
						}
						HTMLPrinter.addPageEpilog(buffer);
						return new JavadocBrowserInformationControlInput(null, element, buffer.toString(), 0);
					}
				}

			} catch (JavaModelException e) {
				JavaPlugin.log(e);
			}
		}
		return null;
	}

	private void addConstantOrDefaultValue(StringBuilder buffer, IJavaElement element) throws JavaModelException {
		int elementType= element.getElementType();
		if ((elementType != IJavaElement.FIELD)
				&& (elementType != IJavaElement.METHOD)) {
			return;
		}
		ITypeRoot typeRoot= null;
		Region nameRegion= null;
		if (element instanceof IMember) {
			// typeRoot and nameRegion - used to retrieve the constant/default value of the element.
			// Useful only when the element is declared in the active CU as JavadocHover#getHoveredASTNode
			// uses SharedASTProvider.WAIT_ACTIVE_ONLY. Otherwise, bindings are created.
			typeRoot= ((IMember) element).getTypeRoot();
			ISourceRange nameRange= ((ISourceReference) element).getNameRange();
			if (SourceRange.isAvailable(nameRange)) {
				nameRegion= new Region(nameRange.getOffset(), nameRange.getLength());
			}
		}
		if (elementType == IJavaElement.FIELD) {
			String constantValue= JavadocHover.getConstantValue((IField) element, typeRoot, nameRegion);
			if (constantValue != null) {
				constantValue= HTMLPrinter.convertToHTMLContentWithWhitespace(constantValue);

				buffer.append(JavadocContentAccess2.BlOCK_TAG_TITLE_START);
				buffer.append(JavaTextMessages.AbstractJavaCompletionProposal_value);
				buffer.append(JavadocContentAccess2.BlOCK_TAG_TITLE_END);
				buffer.append(JavadocContentAccess2.BlOCK_TAG_ENTRY_START);
				buffer.append(constantValue);
				buffer.append(JavadocContentAccess2.BlOCK_TAG_ENTRY_END);
			}
		} else if (elementType == IJavaElement.METHOD) {
			String defaultValue;
			try {
				defaultValue= JavadocHover.getAnnotationMemberDefaultValue((IMethod) element, typeRoot, nameRegion);
			} catch (JavaModelException e) {
				defaultValue= null;
			}
			if (defaultValue != null) {
				defaultValue= HTMLPrinter.convertToHTMLContentWithWhitespace(defaultValue);

				buffer.append(JavadocContentAccess2.BlOCK_TAG_TITLE_START);
				buffer.append(JavaTextMessages.AbstractJavaCompletionProposal_default);
				buffer.append(JavadocContentAccess2.BlOCK_TAG_TITLE_END);
				buffer.append(JavadocContentAccess2.BlOCK_TAG_ENTRY_START);
				buffer.append(defaultValue);
				buffer.append(JavadocContentAccess2.BlOCK_TAG_ENTRY_END);
			}
		}
	}

	/**
	 * Returns the style information for displaying HTML (Javadoc) content.
	 *
	 * @return the CSS styles
	 * @since 3.3
	 */
	protected String getCSSStyles() {
		if (fgCSSStyles == null) {
			Bundle bundle= Platform.getBundle(JavaPlugin.getPluginId());
			URL url= bundle.getEntry("/JavadocHoverStyleSheet.css"); //$NON-NLS-1$
			if (url != null) {
				BufferedReader reader= null;
				try {
					url= FileLocator.toFileURL(url);
					reader= new BufferedReader(new InputStreamReader(url.openStream()));
					StringBuilder buffer= new StringBuilder(200);
					String line= reader.readLine();
					while (line != null) {
						buffer.append(line);
						buffer.append('\n');
						line= reader.readLine();
					}
					fgCSSStyles= buffer.toString();
				} catch (IOException ex) {
					JavaPlugin.log(ex);
				} finally {
					try {
						if (reader != null)
							reader.close();
					} catch (IOException e) {
					}
				}

			}
		}
		String css= fgCSSStyles;
		if (css != null) {
			FontData fontData= JFaceResources.getFontRegistry().getFontData(PreferenceConstants.APPEARANCE_JAVADOC_FONT)[0];
			css= HTMLPrinter.convertTopLevelFont(css, fontData);
		}
		return css;
	}

	@Override
	public int getContextInformationPosition() {
		if (getContextInformation() == null)
			return getReplacementOffset() - 1;
		return getReplacementOffset() + getCursorPosition();
	}

	/**
	 * Gets the replacement offset.
	 * @return Returns a int
	 */
	public int getReplacementOffset() {
		return fReplacementOffset;
	}

	/**
	 * Sets the replacement offset.
	 * @param replacementOffset The replacement offset to set
	 */
	public void setReplacementOffset(int replacementOffset) {
		Assert.isTrue(replacementOffset >= 0);
		fReplacementOffset= replacementOffset;
	}

	@Override
	public int getPrefixCompletionStart(IDocument document, int completionOffset) {
		return getReplacementOffset();
	}

	/**
	 * Gets the replacement length.
	 * @return Returns a int
	 */
	public int getReplacementLength() {
		return fReplacementLength;
	}

	/**
	 * Sets the replacement length.
	 * @param replacementLength The replacementLength to set
	 */
	public void setReplacementLength(int replacementLength) {
		Assert.isTrue(replacementLength >= 0);
		fReplacementLength= replacementLength;
	}

	/**
	 * Gets the replacement string.
	 * @return Returns a String
	 */
	public String getReplacementString() {
		return fReplacementString;
	}

	/**
	 * Sets the replacement string.
	 * @param replacementString The replacement string to set
	 */
	public void setReplacementString(String replacementString) {
		Assert.isNotNull(replacementString);
		fReplacementString= replacementString;
	}

	@Override
	public CharSequence getPrefixCompletionText(IDocument document, int completionOffset) {
		if (!isCamelCaseMatching())
			return getReplacementString();

		String prefix= getPrefix(document, completionOffset);
		return getCamelCaseCompound(prefix, getReplacementString());
	}

	@Override
	public Image getImage() {
		return fImage;
	}

	/**
	 * Sets the image.
	 * @param image The image to set
	 */
	public void setImage(Image image) {
		fImage= image;
	}

	@Override
	public boolean isValidFor(IDocument document, int offset) {
		return validate(document, offset, null);
	}

	@Override
	public boolean validate(IDocument document, int offset, DocumentEvent event) {

		if (!isOffsetValid(offset))
			return fIsValidated= false;

		fIsValidated= isValidPrefix(getPrefix(document, offset));

		if (fIsValidated && event != null) {
			// adapt replacement range to document change
			int delta= (event.fText == null ? 0 : event.fText.length()) - event.fLength;
			final int newLength= Math.max(getReplacementLength() + delta, 0);
			setReplacementLength(newLength);
		}

		return fIsValidated;
	}

	/**
	 * Checks whether the given offset is valid for this proposal.
	 *
	 * @param offset the caret offset
	 * @return <code>true</code> if the offset is valid for this proposal
	 * @since 3.5
	 */
	protected boolean isOffsetValid(int offset) {
		return getReplacementOffset() <= offset;
	}

	/**
	 * Checks whether <code>pattern</code> is a valid pattern for this proposal. Usually, while code
	 * completion is in progress, the user types and edits the pattern in the document in order to
	 * filter the proposal list. From {@link #validate(IDocument, int, DocumentEvent) }, the current
	 * pattern in the document is extracted and this method is called to find out whether the
	 * proposal is still valid.
	 * <p>
	 * The default implementation checks if <code>pattern</code> matches the proposal's
	 * {@link #getDisplayString() display string} using the {@link #isPrefix(String, String) }
	 * method.
	 * </p>
	 *
	 * @param pattern the current pattern in the document
	 * @return <code>true</code> if <code>pattern</code> is a valid match for this proposal
	 */
	protected boolean isValidPrefix(String pattern) {
		/*
		 * See http://dev.eclipse.org/bugs/show_bug.cgi?id=17667
		 * why we do not use the replacement string.
		 * String word= fReplacementString;
		 *
		 * Besides that bug we also use the display string
		 * for performance reasons, as computing the
		 * replacement string can be expensive.
		 */
		return isPrefix(pattern, TextProcessor.deprocess(getDisplayString()));
	}

	/**
	 * Gets the proposal's relevance.
	 * @return Returns a int
	 */
	@Override
	public int getRelevance() {
		if (fPatternMatchRule == SearchPattern.R_SUBSTRING_MATCH) {
			return fRelevance - 500;
		}
		if (fPatternMatchRule == SearchPattern.R_SUBWORD_MATCH) {
			return fRelevance - 1000;
		}
		return fRelevance;
	}

	/**
	 * Sets the proposal's relevance.
	 * @param relevance The relevance to set
	 */
	public void setRelevance(int relevance) {
		fRelevance= relevance;
	}

	/**
	 * Returns the text in <code>document</code> from {@link #getReplacementOffset()} to
	 * <code>offset</code>. Returns the empty string if <code>offset</code> is before the
	 * replacement offset or if an exception occurs when accessing the document.
	 *
	 * @param document the document
	 * @param offset the offset
	 * @return the prefix
	 * @since 3.2
	 */
	protected String getPrefix(IDocument document, int offset) {
		try {
			int length= offset - getReplacementOffset();
			if (length > 0)
				return document.get(getReplacementOffset(), length);
		} catch (BadLocationException x) {
		}
		return ""; //$NON-NLS-1$
	}

	/**
	 * Case insensitive matching of the <code>pattern</code> within the given <code>string</code>.
	 *
	 * @param pattern the pattern
	 * @param string the string to look for the pattern
	 * @return <code>true</code> if the given pattern matches the string as a prefix, as a CamelCase
	 *         match, or as a substring pattern and <code>false</code> if <code>pattern</code> is
	 *         longer than <code>string</code> or if the pattern doesn't match the string based on
	 *         any of these rules
	 * @since 3.2
	 */
	protected boolean isPrefix(String pattern, String string) {
		if (pattern == null || string == null || pattern.length() > string.length())
			return false;
		fPatternMatchRule= getPatternMatchRule(pattern, string);
		return fPatternMatchRule != -1;
	}

	/**
	 * Matches the given <code>pattern</code> in <code>string</code> and returns the match rule.
	 *
	 * @param pattern the pattern to match
	 * @param string the string to look for the pattern
	 * @return the match rule used to match the given <code>pattern</code> in <code>string</code>,
	 *         or -1 if the <code>pattern</code> doesn't match the <code>string</code> based on any
	 *         rule
	 * @since 3.12
	 */
	protected int getPatternMatchRule(String pattern, String string) {
		String start;
		try {
			start= string.substring(0, pattern.length());
		} catch (StringIndexOutOfBoundsException e) {
			String message= "Error retrieving proposal text.\nDisplay string:\n" + string + "\nPattern:\n" + pattern; //$NON-NLS-1$//$NON-NLS-2$
			JavaPlugin.log(new Status(IStatus.ERROR, JavaPlugin.getPluginId(), IStatus.OK, message, e));
			return -1;
		}
		if (start.equalsIgnoreCase(pattern)) {
			return SearchPattern.R_PREFIX_MATCH;
		} else if (isCamelCaseMatching() && CharOperation.camelCaseMatch(pattern.toCharArray(), string.toCharArray())) {
			return SearchPattern.R_CAMELCASE_MATCH;
		} else if (isSubstringMatching() && CharOperation.substringMatch(pattern.toCharArray(), string.toCharArray())) {
			return SearchPattern.R_SUBSTRING_MATCH;
		} else if (isSubwordMatching() && CharOperation.subWordMatch(pattern.toCharArray(), string.toCharArray())) {
			return SearchPattern.R_SUBWORD_MATCH;
		} else {
			return -1;
		}
	}

	/**
	 * Matches <code>prefix</code> against <code>string</code> and replaces the matched region
	 * by prefix. Case is preserved as much as possible. This method returns <code>string</code> if camel case completion
	 * is disabled. Examples when camel case completion is enabled:
	 * <ul>
	 * <li>getCamelCompound("NuPo", "NullPointerException") -> "NuPointerException"</li>
	 * <li>getCamelCompound("NuPoE", "NullPointerException") -> "NuPoException"</li>
	 * <li>getCamelCompound("hasCod", "hashCode") -> "hasCode"</li>
	 * </ul>
	 *
	 * @param prefix the prefix to match against
	 * @param string the string to match
	 * @return a compound of prefix and any postfix taken from <code>string</code>
	 * @since 3.2
	 */
	protected final String getCamelCaseCompound(String prefix, String string) {
		if (prefix.length() > string.length())
			return string;

		// a normal prefix - no camel case logic at all
		String start= string.substring(0, prefix.length());
		if (start.equalsIgnoreCase(prefix))
			return string;

		final char[] patternChars= prefix.toCharArray();
		final char[] stringChars= string.toCharArray();

		for (int i= 1; i <= stringChars.length; i++)
			if (CharOperation.camelCaseMatch(patternChars, 0, patternChars.length, stringChars, 0, i))
				return prefix + string.substring(i);

		// Not a camel case match at all.
		// This should not happen -> stay with the default behavior
		return string;
	}

	/**
	 * Returns true if camel case matching is enabled.
	 *
	 * @return <code>true</code> if camel case matching is enabled
	 * @since 3.2
	 */
	protected boolean isCamelCaseMatching() {
		String value= JavaCore.getOption(JavaCore.CODEASSIST_CAMEL_CASE_MATCH);
		return JavaCore.ENABLED.equals(value);
	}

	/**
	 * Returns true if substring matching is enabled.
	 *
	 * @return <code>true</code> if substring matching is enabled
	 * @since 3.12
	 */
	protected boolean isSubstringMatching() {
		return JavaManipulationPlugin.CODEASSIST_SUBSTRING_MATCH_ENABLED;
	}

	private boolean isSubwordMatching() {
		String value= JavaCore.getOption(JavaCore.CODEASSIST_SUBWORD_MATCH);
		return JavaCore.ENABLED.equals(value);
	}

	protected static boolean insertCompletion() {
		IPreferenceStore preference= JavaPlugin.getDefault().getPreferenceStore();
		return preference.getBoolean(PreferenceConstants.CODEASSIST_INSERT_COMPLETION);
	}

	private static Color getForegroundColor() {
		IPreferenceStore preference= JavaPlugin.getDefault().getPreferenceStore();
		RGB rgb= PreferenceConverter.getColor(preference, PreferenceConstants.CODEASSIST_REPLACEMENT_FOREGROUND);
		JavaTextTools textTools= JavaPlugin.getDefault().getJavaTextTools();
		return textTools.getColorManager().getColor(rgb);
	}

	private static Color getBackgroundColor() {
		IPreferenceStore preference= JavaPlugin.getDefault().getPreferenceStore();
		RGB rgb= PreferenceConverter.getColor(preference, PreferenceConstants.CODEASSIST_REPLACEMENT_BACKGROUND);
		JavaTextTools textTools= JavaPlugin.getDefault().getJavaTextTools();
		return textTools.getColorManager().getColor(rgb);
	}

	private void repairPresentation(ITextViewer viewer) {
		if (fRememberedStyleRange != null) {
			if (viewer instanceof ITextViewerExtension2) {
				// attempts to reduce the redraw area
				ITextViewerExtension2 viewer2= (ITextViewerExtension2)viewer;
				viewer2.invalidateTextPresentation(fRememberedStyleRange.start, fRememberedStyleRange.length);
			} else
				viewer.invalidateTextPresentation();
		}
	}

	private void updateStyle(ITextViewer viewer) {
		StyledText text= viewer.getTextWidget();
		int widgetOffset= getWidgetOffset(viewer, fRememberedStyleRange.start);
		StyleRange range= new StyleRange(fRememberedStyleRange);
		range.start= widgetOffset;
		range.length= fRememberedStyleRange.length;
		StyleRange currentRange= text.getStyleRangeAtOffset(widgetOffset);
		if (currentRange != null) {
			range.strikeout= currentRange.strikeout;
			range.underline= currentRange.underline;
			range.fontStyle= currentRange.fontStyle;
		}

		// http://dev.eclipse.org/bugs/show_bug.cgi?id=34754
		try {
			text.setStyleRange(range);
		} catch (IllegalArgumentException x) {
			// catching exception as offset + length might be outside of the text widget
			fRememberedStyleRange= null;
		}
	}

	/**
	 * Convert a document offset to the corresponding widget offset.
	 *
	 * @param viewer the text viewer
	 * @param documentOffset the document offset
	 * @return widget offset
	 * @since 3.6
	 */
	private int getWidgetOffset(ITextViewer viewer, int documentOffset) {
		if (viewer instanceof ITextViewerExtension5) {
			ITextViewerExtension5 extension= (ITextViewerExtension5)viewer;
			return extension.modelOffset2WidgetOffset(documentOffset);
		}
		IRegion visible= viewer.getVisibleRegion();
		int widgetOffset= documentOffset - visible.getOffset();
		if (widgetOffset > visible.getLength()) {
			return -1;
		}
		return widgetOffset;
	}


	/**
	 * Creates a style range for the text viewer.
	 *
	 * @param viewer the text viewer
	 * @return the new style range for the text viewer or <code>null</code>
	 * @since 3.6
	 */
	private StyleRange createStyleRange(ITextViewer viewer) {
		StyledText text= viewer.getTextWidget();
		if (text == null || text.isDisposed())
			return null;

		int widgetCaret= text.getCaretOffset();

		int modelCaret= 0;
		if (viewer instanceof ITextViewerExtension5) {
			ITextViewerExtension5 extension= (ITextViewerExtension5) viewer;
			modelCaret= extension.widgetOffset2ModelOffset(widgetCaret);
		} else {
			IRegion visibleRegion= viewer.getVisibleRegion();
			modelCaret= widgetCaret + visibleRegion.getOffset();
		}

		if (modelCaret >= getReplacementOffset() + getReplacementLength())
			return null;

		int length= getReplacementOffset() + getReplacementLength() - modelCaret;

		Color foreground= getForegroundColor();
		Color background= getBackgroundColor();

		return new StyleRange(modelCaret, length, foreground, background);
	}

	@Override
	public void selected(final ITextViewer viewer, boolean smartToggle) {
		repairPresentation(viewer);
		fRememberedStyleRange= null;

		if (insertCompletion() == smartToggle) {
			StyleRange range= createStyleRange(viewer);
			if (range == null)
				return;

			fRememberedStyleRange= range;

			if (viewer instanceof ITextViewerExtension4) {
				if (fTextPresentationListener == null) {
					fTextPresentationListener= textPresentation -> {
						fRememberedStyleRange= createStyleRange(viewer);
						if (fRememberedStyleRange != null)
							textPresentation.mergeStyleRange(fRememberedStyleRange);
					};
					((ITextViewerExtension4)viewer).addTextPresentationListener(fTextPresentationListener);
				}
				repairPresentation(viewer);
			} else
				updateStyle(viewer);
		}
	}

	@Override
	public void unselected(ITextViewer viewer) {
		if (fTextPresentationListener != null) {
			((ITextViewerExtension4)viewer).removeTextPresentationListener(fTextPresentationListener);
			fTextPresentationListener= null;
		}
		repairPresentation(viewer);
		fRememberedStyleRange= null;
	}

	@Override
	public IInformationControlCreator getInformationControlCreator() {
		Shell shell= JavaPlugin.getActiveWorkbenchShell();
		if (shell == null || !BrowserInformationControl.isAvailable(shell))
			return null;

		if (fCreator == null) {
			/*
			 * FIXME: Take control creators (and link handling) out of JavadocHover,
			 * see https://bugs.eclipse.org/bugs/show_bug.cgi?id=232024
			 */
			JavadocHover.PresenterControlCreator presenterControlCreator= new JavadocHover.PresenterControlCreator(getSite());
			fCreator= new JavadocHover.HoverControlCreator(presenterControlCreator, true);
		}
		return fCreator;
	}

	private IWorkbenchSite getSite() {
		IWorkbenchPage page= JavaPlugin.getActivePage();
		if (page != null) {
			IWorkbenchPart part= page.getActivePart();
			if (part != null)
				return part.getSite();
		}
		return null;
	}

	public String getSortString() {
		return fSortString;
	}

	protected void setSortString(String string) {
		fSortString= string;
	}

	protected ITextViewer getTextViewer() {
		return fTextViewer;
	}

	protected boolean isToggleEating() {
		return fToggleEating;
	}

	/**
	 * Sets up a simple linked mode at {@link #getCursorPosition()} and an exit policy that will
	 * exit the mode when <code>closingCharacter</code> is typed and an exit position at
	 * <code>getCursorPosition() + 1</code>.
	 *
	 * @param document the document
	 * @param closingCharacter the exit character
	 */
	protected void setUpLinkedMode(IDocument document, char closingCharacter) {
		if (getTextViewer() != null && autocloseBrackets()) {
			int offset= getReplacementOffset() + getCursorPosition();
			int exit= getReplacementOffset() + getReplacementString().length();
			try {
				LinkedPositionGroup group= new LinkedPositionGroup();
				group.addPosition(new LinkedPosition(document, offset, 0, LinkedPositionGroup.NO_STOP));

				LinkedModeModel model= new LinkedModeModel();
				model.addGroup(group);
				model.forceInstall();

				LinkedModeUI ui= new EditorLinkedModeUI(model, getTextViewer());
				ui.setSimpleMode(true);
				ui.setExitPolicy(new ExitPolicy(closingCharacter, document));
				ui.setExitPosition(getTextViewer(), exit, 0, Integer.MAX_VALUE);
				ui.setCyclingMode(LinkedModeUI.CYCLE_NEVER);
				ui.enter();
			} catch (BadLocationException x) {
				JavaPlugin.log(x);
			}
		}
	}

	protected boolean autocloseBrackets() {
		IPreferenceStore preferenceStore= JavaPlugin.getDefault().getPreferenceStore();
		return preferenceStore.getBoolean(PreferenceConstants.EDITOR_CLOSE_BRACKETS);
	}

	protected void setDisplayString(String string) {
		fDisplayString= new StyledString(string);
	}

	/*
	 * @since 3.4
	 */
	@Override
	public StyledString getStyledDisplayString() {
		return fDisplayString;
	}

	public void setStyledDisplayString(StyledString text) {
		fDisplayString= text;
	}

	@Override
	public StyledString getStyledDisplayString(IDocument document, int offset, BoldStylerProvider boldStylerProvider) {
		StyledString styledDisplayString= new StyledString();
		styledDisplayString.append(getStyledDisplayString());

		String pattern= getPatternToEmphasizeMatch(document, offset);
		if (pattern != null && pattern.length() > 0) {
			String displayString= styledDisplayString.getString();
			int patternMatchRule= getPatternMatchRule(pattern, displayString);
			int[] matchingRegions= SearchPattern.getMatchingRegions(pattern, displayString, patternMatchRule);
			Strings.markMatchingRegions(styledDisplayString, 0, matchingRegions, boldStylerProvider.getBoldStyler());
		}
		return styledDisplayString;
	}

	/**
	 * Computes the token at the given <code>offset</code> in <code>document</code> to emphasize the
	 * ranges matching this token in proposal's display string.
	 *
	 * @param document the document where content assist is invoked
	 * @param offset the offset in the document at current caret location
	 * @return the token at the given <code>offset</code> in <code>document</code> to be used for
	 *         emphasizing matching ranges in proposal's display string
	 * @since 3.12
	 */
	protected String getPatternToEmphasizeMatch(IDocument document, int offset) {
		int start= getPrefixCompletionStart(document, offset);
		int patternLength= offset - start;
		String pattern= null;
		try {
			pattern= document.get(start, patternLength);
		} catch (BadLocationException e) {
			// return null
		}
		return pattern;
	}

	@Override
	public String toString() {
		return getDisplayString();
	}

	/**
	 * Returns the java element proposed by the receiver, possibly <code>null</code>.
	 *
	 * @return the java element proposed by the receiver, possibly <code>null</code>
	 */
	public IJavaElement getJavaElement() {
		if (getProposalInfo() != null)
			try {
				return getProposalInfo().getJavaElement();
			} catch (JavaModelException x) {
				JavaPlugin.log(x);
			}
		return null;
	}

	/**
	 * Tells whether required proposals are supported by this proposal.
	 *
	 * @return <code>true</code> if required proposals are supported by this proposal
	 * @see CompletionProposal#getRequiredProposals()
	 * @since 3.3
	 */
	protected boolean isSupportingRequiredProposals() {
		if (fInvocationContext == null)
			return false;

		ProposalInfo proposalInfo= getProposalInfo();
		if (!(proposalInfo instanceof MemberProposalInfo)
				&& !(proposalInfo instanceof AnonymousTypeProposalInfo))
			return false;

		CompletionProposal proposal= ((MemberProposalInfo)proposalInfo).fProposal;
		return proposal != null && (proposal.getKind() == CompletionProposal.METHOD_REF || proposal.getKind() == CompletionProposal.FIELD_REF || proposal.getKind() == CompletionProposal.TYPE_REF || proposal.getKind() == CompletionProposal.CONSTRUCTOR_INVOCATION || proposal.getKind() == CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION);
	}

	@Override
	public boolean isAutoInsertable() {
		if (fInvocationContext == null) {
			return false;
		}
		if (insertCompletion()) {
			return true;
		}
		IDocument document = fInvocationContext.getDocument();
		if (document == null) {
			return false;
		}
		try {
			String documentString = document.get(getReplacementOffset(), getReplacementLength());
			if(documentString == null) {
				return false;
			}
			String replacementString = getReplacementString();
			return replacementString.startsWith(documentString);
		} catch (BadLocationException e) {
			JavaPlugin.log(e);
			return false;
		}
	}
}
