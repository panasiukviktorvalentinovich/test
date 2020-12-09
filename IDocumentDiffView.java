package com.smartbear.ccollab.webclient.reviewfile.diff.document;

import com.google.inject.ImplementedBy;
import com.smartbear.ccollab.datamodel.client.IVersion;
import com.smartbear.ccollab.datamodel.client.unversioned.IDocumentPageDiff;
import com.smartbear.ccollab.webclient.reviewfile.diff.DiffSide;
import com.smartbear.ccollab.webclient.reviewfile.diff.document.side.DocumentDiffSideActivity;
import com.smartbear.ccollab.webclient.reviewfile.diff.document.side.DocumentDiffSideViewImpl;
import com.smartbear.ccollab.webclient.reviewfile.diff.document.side.IDocumentDiffSideView;
import com.smartbear.ccollab.webclient.reviewfile.diff.pagedimage.IPagedImageDiffView;
import com.smartbear.ccollab.datamodel.client.PagedImageDiffZoomLevel;
import com.smartbear.ccollab.webclient.reviewfile.diff.pagedimage.side.IPagedImageDiffSideView;
import com.smartbear.ccollab.webclient.reviewfile.fileToolbar.search.ISearchableDiff;

/**
 * Document diff view interface
 */
@ImplementedBy(DocumentDiffViewImpl.class)
public interface IDocumentDiffView 
	extends 
		IPagedImageDiffView<
			IDocumentDiffView, 
			DocumentDiffActivity,
			IDocumentDiffSideView,
			DocumentDiffSideActivity
			>, 
		ISearchableDiff //causes search box to appear in diff menu 
		{

	@Override
	IDocumentDiffSideView getLeft();

	@Override
	IDocumentDiffSideView getRight();

	/**
	 * Set page currently displaying at left side
	 * @param side side @nonnull
	 * @param page page displaying
	 * @param numPages number of available pages
	 */
	void setPage(DiffSide side, int page, int numPages);

	 void setLoadingPage(DiffSide side,int page);
	 
	 void hideLoadingPage(DiffSide side);
	
	/**
	 * Sets if one side's pagination is enabled or disabled
	 * @param side side @nonnull
	 * @param enabled
	 */
	void setPaginationEnabled(DiffSide side, boolean enabled);


	/**
	 * Pass the information from Document to PageImaged
	 * @param type IPagedImageDiffSideView.ToogleButtonType
	 * @param isLock  if the lock for viewports
	 * @param afterVersion after version
	 * @param beforeVersion before  version
	 * @param afterActivityPage  number of pages in afterActivity
	 * @param beforeActivityPage number of pages in beforeActivity
	 * @param afterSide  side
	 * @param beforeSide side
	 */
	void	setMouseToolType(IPagedImageDiffSideView.ToogleButtonType type,
							 boolean isLock,
							 IVersion afterVersion,
							 IVersion beforeVersion,
							 int afterActivityPage,
							 int beforeActivityPage,
							 DiffSide afterSide,
							 DiffSide beforeSide,
							 IDocumentPageDiff beforeDiff,
							 IDocumentPageDiff afterDiff
							 );
	/**
	 * Pass the zoom level from Document to PageImaged
	 * @param  zoomLevel
	 */
	void setZoomLevel(PagedImageDiffZoomLevel zoomLevel);
	
}
