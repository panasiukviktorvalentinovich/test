package com.smartbear.ccollab.webclient.reviewfile.diff.document;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ChangeHandler;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.DomEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.event.dom.client.KeyUpHandler;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.IntegerBox;
import com.google.gwt.user.client.ui.SplitLayoutPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Singleton;
import com.smartbear.ccollab.datamodel.client.IVersion;
import com.smartbear.ccollab.datamodel.client.unversioned.IDocumentPageDiff;
import com.smartbear.ccollab.webclient.common.WebClientUtils;
import com.smartbear.ccollab.webclient.reviewfile.diff.DiffSide;
import com.smartbear.ccollab.webclient.reviewfile.diff.DiffSide.IDiffSideVisitor;
import com.smartbear.ccollab.webclient.reviewfile.diff.PagerFactory.IPager;
import com.smartbear.ccollab.webclient.reviewfile.diff.coordinate.PaginationWidget;
import com.smartbear.ccollab.webclient.reviewfile.diff.document.side.DocumentDiffSideActivity;
import com.smartbear.ccollab.webclient.reviewfile.diff.document.side.DocumentDiffSideViewImpl;
import com.smartbear.ccollab.webclient.reviewfile.diff.document.side.IDocumentDiffSideView;
import com.smartbear.ccollab.datamodel.client.PagedImageDiffZoomLevel;
import com.smartbear.ccollab.webclient.reviewfile.diff.pagedimage.PagedImageSideBySideDiffViewImpl;
import com.smartbear.ccollab.webclient.reviewfile.diff.pagedimage.side.IPagedImageDiffSideView;
import com.smartbear.ccollab.webclient.reviewfile.fileToolbar.options.IFileOptionsView;

/**
 * Document diff view implementation
 */
@Singleton
public class DocumentDiffViewImpl extends PagedImageSideBySideDiffViewImpl<
	IDocumentDiffView, 
	DocumentDiffActivity,
	IDocumentDiffSideView,
	DocumentDiffSideActivity
	> implements IDocumentDiffView {

	private static DocumentDiffViewImplUiBinder uiBinder = GWT.create(DocumentDiffViewImplUiBinder.class);

	@UiField IStyle style;
	@UiField SplitLayoutPanel layoutPanel;

	@UiField DocumentDiffSideViewImpl leftSide;
	@UiField DocumentDiffSideViewImpl rightSide;

	@UiField PaginationWidget leftPagination;
	@UiField PaginationWidget rightPagination;
	
    private final IPager pager = new IPager() {
    	@Override
    	public void firstPage() {
			presenter.changePage(1, DiffSide.LEFT);
			presenter.changePage(1, DiffSide.RIGHT);
    	}
    	
    	@Override
    	public void lastPage() {
    		// page will be clipped to last
    		presenter.changePage(Integer.MAX_VALUE, DiffSide.LEFT);
        	presenter.changePage(Integer.MAX_VALUE, DiffSide.RIGHT);
    	}
    	
    	@Override
    	public void pageDown() {
    		presenter.showNextPage(DiffSide.LEFT);
    	}
    	
    	@Override
    	public void pageUp() {
    		presenter.showPrevPage(DiffSide.LEFT);
    	}
    	
	};

	interface DocumentDiffViewImplUiBinder extends UiBinder<Widget, DocumentDiffViewImpl> {
	}

	public DocumentDiffViewImpl() {
		super();
		initWidget(uiBinder.createAndBindUi(this));

		leftSide.setPagination(leftPagination);
		rightSide.setPagination(rightPagination);

		leftPagination.addPrevButtonClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                presenter.showPrevPage(DiffSide.LEFT) ;
            }
        });

		leftPagination.addNextButtonClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                presenter.showNextPage(DiffSide.LEFT);
            }
        });

		leftPagination.addPageBoxChangeHandler(new ChangeHandler() {
            @Override
            public void onChange(ChangeEvent event) {
                handleChangePage(event, DiffSide.LEFT);
            }
        });
		
		rightPagination.addPrevButtonClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                presenter.showPrevPage(DiffSide.RIGHT);
            }
        });

        rightPagination.addNextButtonClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
            	presenter.showNextPage(DiffSide.RIGHT);
            }
        });

        rightPagination.addPageBoxChangeHandler(new ChangeHandler() {
            @Override
            public void onChange(ChangeEvent event) {
                handleChangePage(event, DiffSide.RIGHT);
            }
        });
        
		if (WebClientUtils.isIE()) {
			leftPagination.addPageBoxKeyUpHandler(new KeyUpHandler(){
				@Override
				public void onKeyUp(KeyUpEvent event) {
					handleChangePage(event, DiffSide.LEFT);
				}
			});
	        
	        rightPagination.addPageBoxKeyUpHandler(new KeyUpHandler() {
				@Override
				public void onKeyUp(KeyUpEvent event) {
					handleChangePage(event, DiffSide.RIGHT);
				}
			});
		}
	}
	
	private void handleChangePage(KeyUpEvent event, DiffSide side) {
		if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
			handleChangePage((DomEvent<?>)event, side);
		}
	}

	private void handleChangePage(DomEvent<?> event, DiffSide side) {
	    IntegerBox pageBox = (IntegerBox) event.getSource();
        final Integer value = pageBox.getValue();
        if (value == null)
            return; //couldn't parse as an integer - ignore
        else
            presenter.changePage(value, side, null);
    }

	public void setZoomLevel(PagedImageDiffZoomLevel zoomLevel){
		leftSide.setZoomLevel(zoomLevel);
		rightSide.setZoomLevel(zoomLevel);
	}
	
	public void setMouseToolType(IPagedImageDiffSideView.ToogleButtonType type,
								 boolean isLock,
								 IVersion afterVersion,
								 IVersion beforeVersion,
								 int afterActivityPage ,
								 int beforeActivityPage ,
								 DiffSide afterSide,
								 DiffSide beforeSide,
								 IDocumentPageDiff beforeDiff,
								 IDocumentPageDiff afterDiff
								 ){
		leftSide.setMouseToolType(type, isLock);
		rightSide.setMouseToolType(type, isLock);

	    if(type == IPagedImageDiffSideView.ToogleButtonType.DRAG) {
			if(!isLock) {
				doTwoPanelDragAndZoom(leftSide, rightSide,   beforeDiff, afterDiff);
				doTwoPanelDragAndZoom(rightSide, leftSide,   afterDiff, beforeDiff);
			}else{
				doDragAndZoom(leftSide);
				doDragAndZoom(rightSide);
			}
		}
		if(type == IPagedImageDiffSideView.ToogleButtonType.POINTER){
		 	if(!isLock) {
				doTwoPanelScrollingAndIteratePages(layoutPanel, leftSide, rightSide, afterVersion, beforeVersion, afterActivityPage, beforeActivityPage,
						afterSide, beforeSide, presenter);
				doTwoPanelScrollingAndIteratePages(layoutPanel, rightSide, leftSide, beforeVersion, afterVersion, beforeActivityPage, afterActivityPage,
						beforeSide, afterSide, presenter);
			}else{
				doOnePanelScrollingAndIteratePages(layoutPanel, leftSide, rightSide, afterVersion, beforeVersion, afterActivityPage, beforeActivityPage,
						afterSide, beforeSide, presenter);
				doOnePanelScrollingAndIteratePages(layoutPanel, rightSide, leftSide, beforeVersion, afterVersion, beforeActivityPage, afterActivityPage,
						beforeSide, afterSide, presenter);
			}
		}
	}

	@Override
	protected void onEnsureDebugId(String baseID) {
		super.onEnsureDebugId(baseID);
		layoutPanel.ensureDebugId(baseID + ".layout");

		leftSide .ensureDebugId(baseID + ".left");
		rightSide.ensureDebugId(baseID + ".right");

		leftPagination .ensureDebugId(baseID + ".leftPagination");
		rightPagination.ensureDebugId(baseID + ".rightPagination");
	}
	
	@Override
	public void setLeftVisible(boolean visible) {
		super.setLeftVisible(visible);
		leftPagination.setVisible(visible);
		rightPagination.setStyleName(style.rightSide(), visible);
	}
	
	@Override
	public void addOptions(IFileOptionsView options) {
		super.addOptions(options);

		//show "nav diffs" option
		addNavDiffsOption("Document", options);

	}
	
	@Override
	public DocumentDiffSideViewImpl getLeft() {
		return leftSide;
	}
	
	@Override
	public DocumentDiffSideViewImpl getRight() {
		return rightSide;
	}
	
	@Override
	protected SplitLayoutPanel getSplitter() {
		return layoutPanel;
	}

	@Override
	public void setPage(DiffSide side, int page, int numPages) {
	    
		PaginationWidget pagination = side.accept(new IDiffSideVisitor<PaginationWidget>() {
				@Override public PaginationWidget visitLeft() { return leftPagination; }
				@Override public PaginationWidget visitRight() { return rightPagination; }
			});

		pagination.setPage(page, numPages);
	}
	
	@Override
	public void setPaginationEnabled(DiffSide side, boolean enabled) {
		PaginationWidget pagination = side.accept(new IDiffSideVisitor<PaginationWidget>() {
			@Override public PaginationWidget visitLeft() { return leftPagination; }
			@Override public PaginationWidget visitRight() { return rightPagination; }
		});

		pagination.setEnabled(enabled);
	}
	
	public void setLoadingPage(DiffSide side, int page) {
		PaginationWidget pagination = side.accept(new IDiffSideVisitor<PaginationWidget>() {
			@Override public PaginationWidget visitLeft() { return leftPagination; }
			@Override public PaginationWidget visitRight() { return rightPagination; }
		});

		pagination.setLoadingPages(page);
	}

	public void hideLoadingPage(DiffSide side) {
		PaginationWidget pagination = side.accept(new IDiffSideVisitor<PaginationWidget>() {
			@Override public PaginationWidget visitLeft() { return leftPagination; }
			@Override public PaginationWidget visitRight() { return rightPagination; }
		});

		pagination.hideLoadingPages();
	}

    static interface IStyle extends CssResource {
        String rightSide();
    }
    
	public IPager getPager() {
		return pager;
	}

	public DocumentDiffActivity getPresenter() {
		return (DocumentDiffActivity)presenter;
	}

}
