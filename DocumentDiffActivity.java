package com.smartbear.ccollab.webclient.reviewfile.diff.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Function;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.gwt.event.shared.EventBus;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.inject.Inject;
import com.smartbear.ccollab.datamodel.client.CoordinateLocator;
import com.smartbear.ccollab.datamodel.client.ILocator;
import com.smartbear.ccollab.datamodel.client.IVersion;
import com.smartbear.ccollab.datamodel.client.unversioned.DisplayOrder;
import com.smartbear.ccollab.datamodel.client.unversioned.IDocumentPageDiff;
import com.smartbear.ccollab.datamodel.client.unversioned.IDocumentSearchResult;
import com.smartbear.ccollab.datamodel.client.unversioned.IPagedCoordinateBox;
import com.smartbear.ccollab.datamodel.client.unversioned.IUnversionedClientApiAsync;
import com.smartbear.ccollab.datamodel.client.unversioned.IUnversionedClientSystemGlobals;
import com.smartbear.ccollab.datamodel.client.unversioned.QualifiedLocator;
import com.smartbear.ccollab.datamodel.client.unversioned.impl.DMPagedCoordinateBox;
import com.smartbear.ccollab.datamodel.client.unversioned.impl.DocumentSearchResult;
import com.smartbear.ccollab.webclient.common.async.AsyncRunnable;
import com.smartbear.ccollab.webclient.common.async.SuccessCallback;
import com.smartbear.ccollab.webclient.common.errorhandler.IErrorHandler;
import com.smartbear.ccollab.webclient.common.errorhandler.PopupErrorHandler;
import com.smartbear.ccollab.webclient.common.metrics.IMetrics;
import com.smartbear.ccollab.webclient.common.refresh.IModelRefreshContext;
import com.smartbear.ccollab.webclient.common.refresh.ModelRefreshManager;
import com.smartbear.ccollab.webclient.common.settings.ILocalSettingsFactory;
import com.smartbear.ccollab.webclient.reviewfile.convos.FileLoadEvent;
import com.smartbear.ccollab.webclient.reviewfile.diff.pagedimage.MultipageUtil;
import com.smartbear.ccollab.webclient.reviewfile.event.DiffReadyEvent;
import com.smartbear.ccollab.webclient.reviewfile.LocatorSelectedEvent;
import com.smartbear.ccollab.webclient.reviewfile.event.SearchReadyEvent;
import com.smartbear.ccollab.webclient.reviewfile.diff.AbstractDiffActivity;
import com.smartbear.ccollab.webclient.reviewfile.diff.DiffSide;
import com.smartbear.ccollab.webclient.reviewfile.diff.document.side.DocumentDiffSideActivity;
import com.smartbear.ccollab.webclient.reviewfile.diff.document.side.DocumentDiffSideActivity.IDocumentDiffSideActivityFactory;
import com.smartbear.ccollab.webclient.reviewfile.diff.document.side.DocumentDiffSideModel;
import com.smartbear.ccollab.webclient.reviewfile.diff.document.side.IDocumentDiffSideView;
import com.smartbear.ccollab.webclient.reviewfile.diff.pagedimage.Coordinate;
import com.smartbear.ccollab.webclient.reviewfile.diff.pagedimage.IPagedImageDiffModel;
import com.smartbear.ccollab.webclient.reviewfile.diff.pagedimage.PagedImageDiffActivity;
import com.smartbear.ccollab.datamodel.client.PagedImageDiffZoomLevel;
import com.smartbear.ccollab.webclient.reviewfile.diff.pagedimage.side.IPagedImageDiffSideView;
import com.smartbear.ccollab.webclient.reviewfile.fileToolbar.search.SearchCompleteEvent;
import com.smartbear.ccollab.webclient.reviewfile.fileToolbar.search.SearchEvent;
import com.smartbear.ccollab.webclient.reviewfile.fileToolbar.search.SearchResultSelectedEvent;
import com.smartbear.collections.Pair;
import com.smartbear.collections.SortedLists;
import com.smartbear.util.ArrayLists;

import javax.annotation.Nonnull;


/**
 * Document diff activity.  Supports searching documents.
 */
public class DocumentDiffActivity extends PagedImageDiffActivity<
	IPagedImageDiffModel, 
	IDocumentDiffView, 
	DocumentDiffActivity,
	DocumentDiffSideModel,
	IDocumentDiffSideView,
	DocumentDiffSideActivity
	> {
	
	static final private Logger LOGGER = Logger.getLogger(DocumentDiffActivity.class.getName());
	
	static final public String TRACE_SUBSYSTEM = "DocumentDiff";
	static final public String TRACE_EVENTGROUP_LOAD_DIFFS = "loadDiffs"; 

	static final private Comparator<IPagedCoordinateBox> BOX_DISPLAY_ORDER = new Comparator<IPagedCoordinateBox>() {
			@Override
			public int compare(IPagedCoordinateBox b1, IPagedCoordinateBox b2) {
				
				//page, vertical top-to-bottom, horizontal left-to-right.
				//Note this isn't correct for all languages
				return ComparisonChain.start()
					.compare(b1.getPage(), b2.getPage())
					.compare(b1.getY(), b2.getY())
					.compare(b1.getX(), b2.getX())
					.result();
			}
		};
	private static final int REFRESH_PROGRESS_INTERVAL_MILLIS = 3000;

	
	final private IDocumentDiffSideActivityFactory sideFactory;
	final private IUnversionedClientApiAsync api;
	final private IErrorHandler errorHandler;
	final private IMetrics metrics;
	final private IUnversionedClientSystemGlobals systemGlobals;

	private ModelRefreshManager<Integer> refreshAfterLoadingPageManager;
	private ModelRefreshManager<Integer> refreshBeforeLoadingPageManager;
	
	private boolean deferredUpdateSearchResult = false;
	private Coordinate deferredAfterScrollTo;
	private Coordinate deferredBeforeScrollTo;

	final private LocatorSelectedEvent.Handler locatorSelectedHandler = new LocatorSelectedEvent.Handler() {
        @Override
        public void onLocatorSelected(final LocatorSelectedEvent event) {
        	if (!(event.getSelectedLocator().getLocator() instanceof CoordinateLocator)) {
        		event.afterFinishingProcess();
				return;
			}
        	
        	final boolean handleAfter = event.getSource() != afterActivity;
        	final boolean handleBefore = beforeActivity != null && event.getSource() != beforeActivity;
        	boolean changeAfterPage = false;

        	if (handleAfter) {
        		CoordinateLocator afterLocator = (CoordinateLocator)event.getSelectedLocator().getLocator();
        		changeAfterPage = afterActivity.getPage() + afterActivity.getView().getOffsetPage() != afterLocator.getPage();
        		if (changeAfterPage) { // do we need to change the page?
        			changePage(afterLocator.getPage(),  afterSide, new SuccessCallback<Void>() {
        				// handle the before change if needed
        				@Override
        				public void onSuccess(Void result) {
        					afterActivity.handleLocatorSelected(event.getSelectedLocator(), DocumentDiffActivity.this);
        					if (handleBefore) { 
        						handleBefore(event.getSelectedLocator());
        					}
							event.afterFinishingProcess();
        				}
					});
        		}
        		else { // just trampoline the event
        			afterActivity.handleLocatorSelected(event.getSelectedLocator(), DocumentDiffActivity.this);
					event.afterFinishingProcess();
        		}
        	}
        	
        	// handle before side if we didn't change the page earlier
        	if (handleBefore && !changeAfterPage) {
        		handleBefore(event.getSelectedLocator());
        	}
     
        }
        
        private void handleBefore(final QualifiedLocator qualifiedLocator) {
    		//figure out locator on before side
    		CoordinateLocator locator;
    		if (qualifiedLocator.getOriginVersionId() == beforeActivity.getVersion().getId()) {
    			//already have mapping to before version - don't need to promote
    			locator = (CoordinateLocator)qualifiedLocator.getOriginLocator();
    		} else if (diffsLoaded) {
    			//promote after -> before
    			locator = beforePromoter.apply((CoordinateLocator)qualifiedLocator.getLocator());
    		} else {
    			locator = (CoordinateLocator)qualifiedLocator.getLocator();
				LOGGER.warning("diff was null when attempting to promote CoordinateLocator " + locator);
    		}

    		//change after page if necessary
    		if (beforeActivity.getPage() + beforeActivity.getView().getOffsetPage() != locator.getPage())
    			changePage(locator.getPage(), beforeSide, result -> beforeActivity.handleLocatorSelected(qualifiedLocator, DocumentDiffActivity.this));
    		else // just pass down the locator
    			beforeActivity.handleLocatorSelected(qualifiedLocator, DocumentDiffActivity.this);
        }
    };

    final private Function<CoordinateLocator,CoordinateLocator> afterPromoter = new Function<CoordinateLocator,CoordinateLocator>(){
        @Override
        public CoordinateLocator apply(CoordinateLocator input) {
        	if (beforeDiff == null || input.getPage() != beforeDiff.getPage()) { // can't promote
        		return input;
        	}
            IPagedCoordinateBox originBox = beforeDiff.boxForCoordinate(input.getX(), input.getY(), input.getPinNumber());
            if(originBox == null) {
                return input;
            }


			IPagedCoordinateBox targetBox = beforeDiff.getOtherSideBox(originBox);
            return toCoordWithShifting(originBox, targetBox, input);
        }
    };

    final private Function<CoordinateLocator,CoordinateLocator> beforePromoter = new Function<CoordinateLocator,CoordinateLocator>(){
        @Override
        public CoordinateLocator apply(CoordinateLocator input) {
        	if (afterDiff == null || input.getPage() != afterDiff.getPage()) { // can't promote
        		return input;
        	}
        	IPagedCoordinateBox originBox = afterDiff.boxForCoordinate(input.getX(), input.getY(), input.getPinNumber());
            if(originBox == null)
                return input;
			IPagedCoordinateBox targetBox = afterDiff.getOtherSideBox(originBox);
			return toCoordWithShifting(originBox, targetBox, input);
        }
    };

    private boolean diffsLoaded = false;
    private IDocumentPageDiff beforeDiff = null;
    private IDocumentPageDiff afterDiff = null;

	/**
	 * Search result @null
	 */
	private List<IDocumentSearchResult> searchResults;
	
	/**
	 * Current search result @null
	 */
	private IDocumentSearchResult currentSearchResult;
	
	/**
	 * after page state for side initialization purposes
	 */
	private int afterPage;

	/**
	 * right page state for side initialization purposes
	 */
	private int beforePage;
	
	private boolean isStart = true;

	/**
	 * Before version initialized in {@link #onStop(IModelRefreshContext)} for use
	 * by {@link #initDiffState(AbstractDiffActivity)} to persist page number when 
	 * switching versions.
	 */
	private IVersion beforeVersionOnStop;

	private  IPagedImageDiffSideView.ToogleButtonType mouseToolType;
	private boolean isLock;
	final private ModelRefreshManager.IModelRefreshManagerFactory<Integer> modelRefreshManagerFactory;
	
	@Inject
	public DocumentDiffActivity(
			IDocumentDiffView view,
			ILocalSettingsFactory localSettingsFactory,
			ModelRefreshManager.IModelRefreshManagerFactory<Integer> modelRefreshManagerFactory,
			IDocumentDiffSideActivityFactory sideFactory,
			IUnversionedClientApiAsync api,
			IUnversionedClientSystemGlobals systemGlobals,
			IErrorHandler errorHandler,
			IMetrics metrics
			) {
		super(view, localSettingsFactory);
		this.sideFactory = sideFactory;
		this.api = api;
		this.systemGlobals = systemGlobals;
		this.errorHandler = errorHandler;
		this.metrics = metrics;
		this.modelRefreshManagerFactory = modelRefreshManagerFactory;
	}
	
	@Override
	public void start(final EventBus eventBus, final IModelRefreshContext<? extends IPagedImageDiffModel> context) {

		//start with page 1 by default, it will be overridden once the diff is loaded and the sides are started
        afterPage = beforePage = 1;

        super.start(eventBus, context);

        final IVersion afterVersion = model.getVersion();
        final IVersion beforeVersion = model.getBeforeVersion();

        view.setNextPageScrollMode(systemGlobals.isNextPageScrollMode());
        
		view.setPage(afterSide, afterPage, afterVersion.getNumPages());
        if (beforeSide != null) {
			view.setPage(beforeSide, beforePage, beforeVersion.getNumPages());
		}
        
      
        if (beforeActivity != null) {
			initDiffPanel(beforeActivity, beforePage, beforeVersion.getNumPages());
		}
		initDiffPanel(afterActivity, afterPage, afterVersion.getNumPages());
		
		runRefresh(afterVersion, beforeVersion, afterPage, beforePage);

		//show selected location
        eventBus.addHandler(LocatorSelectedEvent.TYPE, locatorSelectedHandler);

		eventBus.addHandler(FileLoadEvent.TYPE, event -> {
			if (afterActivity != null && afterActivity.getView().isImagesReady()) {
				afterActivity.getView().clearDiff();

				eventBus.fireEvent(new SearchReadyEvent(true, isStart));

				if (deferredUpdateSearchResult) {
					showSearchResult();
					showCurrentSearchResult(false); //don't change pages or scroll
					deferredUpdateSearchResult = false;
				}
				
				if (deferredAfterScrollTo != null) {
					final List<IPagedCoordinateBox> afterHighlight = filterBoxes(currentSearchResult.getBoxes(), afterPage + afterActivity.getView().getOffsetPage());
					afterActivity.setCurrentSearchResult(deferredAfterScrollTo, afterHighlight);
					deferredAfterScrollTo = null;
				}
				
				if (beforeActivity != null && beforeActivity.getView().isImagesReady()) {
					if (deferredBeforeScrollTo != null) {
						beforeActivity.setCurrentSearchResult(deferredBeforeScrollTo, Collections.emptyList());
						deferredBeforeScrollTo = null;
					}
					beforeActivity.getView().clearDiff();
					
					loadBothDiffs(model.getFile().getReviewId(), afterActivity.getVersion().getId(), beforeActivity.getVersion().getId(), afterPage, beforePage);
				} else if (beforeActivity == null) {
					isStart = false;
				}
			}
		});
	
        //search
        eventBus.addHandler(SearchEvent.TYPE, new SearchEvent.ISearchHandler() {
				@Override
				public void search(final String needle) {
					api.searchDocument(
						model.getFile().getReviewId(),
						afterVersion.getId(),
						needle,
						context.ifActive(errorHandler.defaultError(new SuccessCallback<ArrayList<IDocumentSearchResult>>() {
								@Override
								public void onSuccess(ArrayList<IDocumentSearchResult> searchResults) {
									
									//save result
									DocumentDiffActivity.this.searchResults = searchResults;

									//clear "current" search result
									DocumentDiffActivity.this.currentSearchResult = null;
									
									//find "first" search result - the result after the current location
									int firstSearchResult = Ordering.natural().binarySearch(
										Lists.transform(
											searchResults,
											new Function<IDocumentSearchResult, ILocator>() {
													@Override
													public ILocator apply(IDocumentSearchResult searchResult) {
														return toCoordAtBoxCenter(searchResult.getBoxes().get(0));
													}
												}
											),
										model.getSelectedLocator().getLocator()
										);
									if (firstSearchResult < -searchResults.size()) {
										firstSearchResult = -(firstSearchResult + 2);
									} else if (firstSearchResult < 0) {
										//the common case - there is no search result at the current
										//location
										firstSearchResult = -(firstSearchResult + 1);
									}
								

									//show new search result
									showSearchResult();

									//clear old current search result
									showCurrentSearchResult(false); //don't scroll or change page

									//fire "search complete"
									eventBus.fireEventFromSource(
										new SearchCompleteEvent(
											needle,
											searchResults.size(), 
											firstSearchResult
											),
										DocumentDiffActivity.this
										);
								}
							}))
						);
				}
			});
        eventBus.addHandler(SearchResultSelectedEvent.TYPE, new SearchResultSelectedEvent.ISearchResultSelectedHandler() {
				@Override
				public void searchResultSelected(int resultNum) {
					
					//since we're dealing with events, allow for some slop
					if (searchResults == null) {
						LOGGER.warning("Search result selected but no search results available");
						return; 
					}
					
					if (searchResults.size() <= resultNum) {
						if (LOGGER.isLoggable(Level.WARNING)) {
							LOGGER.warning("Search result " + resultNum + " selected but only " + searchResults.size() + " available");
						}
						return;
					}
					
					//save current search result for when we e.g. switch pages
					currentSearchResult = searchResults.get(resultNum);

					showCurrentSearchResult(true); //change pages if necessary and scroll
					
				}
			});
	}

	private void runRefresh(IVersion afterVersion, IVersion beforeVersion, int afterPage, int beforePage) {
		if (afterVersion.getId() != beforeVersion.getId()) {
			disableDiff();
		}

		if (refreshAfterLoadingPageManager != null && refreshAfterLoadingPageManager.isActive()) {
			refreshAfterLoadingPageManager.stop();
		}
		
		RefreshLoadingPage refreshAfterLoadingPage = new RefreshLoadingPage(afterSide, afterVersion.getId(), model.getFile().getReviewId(), model.getVersion().getId(), model.getBeforeVersion().getId(), afterPage, beforePage);
		refreshAfterLoadingPageManager = modelRefreshManagerFactory.create(refreshAfterLoadingPage);
		refreshAfterLoadingPage.setRefreshManagerRunnable(refreshAfterLoadingPageManager);
		refreshAfterLoadingPageManager.setRefreshInterval(REFRESH_PROGRESS_INTERVAL_MILLIS);
		
		refreshAfterLoadingPageManager.start();

		if (beforeSide != null) {
			if (refreshBeforeLoadingPageManager != null && refreshBeforeLoadingPageManager.isActive()) {
				refreshBeforeLoadingPageManager.stop();
			}
			
			RefreshLoadingPage refreshBeforeLoadingPage = new RefreshLoadingPage(beforeSide, beforeVersion.getId(), model.getFile().getReviewId(), model.getVersion().getId(), model.getBeforeVersion().getId(), afterPage, beforePage);
			refreshBeforeLoadingPageManager = modelRefreshManagerFactory.create(refreshBeforeLoadingPage);
			refreshBeforeLoadingPage.setRefreshManagerRunnable(refreshBeforeLoadingPageManager);
			refreshBeforeLoadingPageManager.setRefreshInterval(REFRESH_PROGRESS_INTERVAL_MILLIS);
		
			refreshBeforeLoadingPageManager.start();
		}
	}

	private void loadBothDiffs(int reviewId, int afterVersionId, int beforeVersionId, int afterPage, int beforePage) {
		for (int afterOffset = 0; afterOffset < afterActivity.getView().getVisibleImagesCount(); afterOffset++) {
			for (int beforeOffset = 0; beforeOffset < beforeActivity.getView().getVisibleImagesCount(); beforeOffset++) {
			
				int finalBeforeOffset = beforeOffset;
				int finalAfterOffset = afterOffset;
				api.getDocumentPageDiffs(reviewId, afterVersionId, beforeVersionId, afterPage + afterOffset, beforePage + beforeOffset,
						context.ifActive(new AsyncCallback<Pair<IDocumentPageDiff, IDocumentPageDiff>>() {
							@Override
							public void onSuccess(Pair<IDocumentPageDiff, IDocumentPageDiff> result) {
								if (afterActivity == null) {
									return;
								}
									
								metrics.trace(TRACE_SUBSYSTEM, TRACE_EVENTGROUP_LOAD_DIFFS, "downloaded from server");
								//Case COLLAB-1170 remove diffs highlighting if "Difference" Checkbox is disabled
								if (!isDifferenceHighlighted()) {
									result.getA().invalidateDiffs();
									result.getB().invalidateDiffs();
								}

								if (beforeSide != null)
									beforeDiff = result.getA();
								afterDiff = result.getB();
								diffsLoaded = true;
								onPageWithDiffs(finalBeforeOffset, finalAfterOffset);

								if (isDiffLoaded(finalBeforeOffset, finalAfterOffset)) {
									// re enable pagination
									// disable navigation
									if (beforeSide != null && beforeActivity != null) {
										beforeActivity.setNavigationEnabled(true);
										view.setPaginationEnabled(beforeSide, true);
									}
									afterActivity.setNavigationEnabled(true);
									view.setPaginationEnabled(afterSide, true);
									
									if (afterVersionId != beforeVersionId && isStart) {
										locatorSelectedHandler.onLocatorSelected(new LocatorSelectedEvent(context.getModel().getSelectedLocator()));
										isStart = false;
									}


									eventBus.fireEvent(new DiffReadyEvent(true));

									metrics.traceEnd(TRACE_SUBSYSTEM, TRACE_EVENTGROUP_LOAD_DIFFS);
								}
							}

							private boolean isDiffLoaded(int finalBeforeOffset, int finalAfterOffset) {
								return (beforeActivity != null && finalBeforeOffset == beforeActivity.getView().getVisibleImagesCount() - 1)
										&& (finalAfterOffset == afterActivity.getView().getVisibleImagesCount() - 1);
							}

							@Override
							public void onFailure(Throwable caught) {
								onDiffLoadError(caught);
							}
						}));
			}
		}
	}

	private void disableDiff() {
		metrics.trace(TRACE_SUBSYSTEM, TRACE_EVENTGROUP_LOAD_DIFFS, "start");

		// disable navigation
		if (beforeSide != null) {
			beforeActivity.setNavigationEnabled(false);
			view.setPaginationEnabled(beforeSide, false);
		}
		afterActivity.setNavigationEnabled(false);
		view.setPaginationEnabled(afterSide, false);
	}

	private void onDiffLoadError(Throwable caught) {
		// stop both sides
		stopAfterSide();
		stopBeforeSide();
		try {
			errorHandler.onError(caught);
		} catch (Throwable unhandled) {
			// popup an error if the errorHandler propagated it (we want to popup an error dialog to tell the user we had an error getting the diffs)
			PopupErrorHandler.popupError("Document diff error", "Unexpected error while getting the page diffs", unhandled);
		}
	}

	public void setMouseToolType(IPagedImageDiffSideView.ToogleButtonType type, boolean isLock, DisplayOrder displayOrder){
		processMouseToolType(  type, isLock, displayOrder);
	}

	private void processMouseToolType(IPagedImageDiffSideView.ToogleButtonType type, boolean isLock, DisplayOrder displayOrder ){
		IVersion afterVersion = model.getVersion();
		IVersion beforeVersion = model.getBeforeVersion();
		int afterActivityPages =  afterActivity==null? 0: afterActivity.getPage();
		int beforeActivityPages = beforeActivity==null? 0: beforeActivity.getPage();
		mouseToolType = type;
		this.isLock = isLock;

		if(displayOrder == DisplayOrder.AFTER_ON_LEFT)
			view.setMouseToolType(  type, isLock, beforeVersion,afterVersion ,beforeActivityPages, afterActivityPages  ,
					  beforeSide,afterSide, afterDiff ,beforeDiff  );
		else
			view.setMouseToolType(  type, isLock, afterVersion, beforeVersion, afterActivityPages  ,
					beforeActivityPages , afterSide, beforeSide, beforeDiff, afterDiff);

	}

	public void setZoomLevel(PagedImageDiffZoomLevel zoomLevel){
		view.setZoomLevel(zoomLevel);
	}

	public void onStop(IModelRefreshContext<? extends IPagedImageDiffModel> context) {
		if (refreshAfterLoadingPageManager != null && refreshAfterLoadingPageManager.isActive()) {
			refreshAfterLoadingPageManager.stop();
		}
		
		if (beforeSide != null) {
			if (refreshBeforeLoadingPageManager != null && refreshBeforeLoadingPageManager.isActive()) {
				refreshBeforeLoadingPageManager.stop();
			}
		}

        //save for use by #initDiffState(...)
        beforeVersionOnStop = model.getBeforeVersion();
        
        //clear search results
        if (searchResults != null) {
        	searchResults = null;
        	showSearchResult(); //cleanup view
        }
        
        //clear current search result
        if (currentSearchResult != null) {
        	currentSearchResult = null;
        	showCurrentSearchResult(false); //cleanup view, don't scroll or change page
        }

        //call super - nulls #model, stops sides
        super.onStop(context);
		
        // don't clear the diff so we can pass it on #initDiffState(...)
	}
	
    @Override
    public void getDiffRelativeTo(QualifiedLocator relativeTo, QualifiedLocator bound, boolean forward, AsyncCallback<QualifiedLocator> callback) {
    	// TODO: if the next one is in the same page it can be retrieved without going to the server
    	api.findNextDocumentDiff(
    			model.getFile().getReviewId(), 
    			model.getVersion().getId(), 
    			model.getBeforeVersion().getId(), 
    			relativeTo, 
    			bound, 
    			forward,
    			context.ifActive(callback));
	}
    
	static private CoordinateLocator toCoordAtBoxCenter(IPagedCoordinateBox box) {
		return new CoordinateLocator(box.getPage(), box.getX() + box.getWidth() / 2, box.getY() + box.getHeight() / 2, box.getPinNumber());
	}

	static private CoordinateLocator toCoordWithShifting(IPagedCoordinateBox originBox, IPagedCoordinateBox targetBox,  CoordinateLocator locator) {
		int shiftX = locator.getX() - originBox.getX();
		int shiftY = locator.getY() - originBox.getY();

		int targetX = (int) targetBox.getX() + shiftX;
		int targetY = (int) targetBox.getY() + shiftY;

		return new CoordinateLocator(targetBox.getPage(), targetX, targetY, targetBox.getPinNumber());
	}

	private void onPageWithDiffs(int beforeOffset, int afterOffset) {
		if(!diffsLoaded || beforeSide == null)
			return;

		//before
		if (beforeActivity != null) {
			beforeActivity.setPromoter(new CoordinatePromoter(beforePromoter));
			beforeActivity.setOtherSidePromoter(new CoordinatePromoter(afterPromoter));
			beforeActivity.setDiffs(beforeOffset, beforeDiff.listDiffs());
		}

		//after
		if (afterActivity != null) {
			afterActivity.setPromoter(new CoordinatePromoter(afterPromoter));
			afterActivity.setDiffs(afterOffset, afterDiff.listDiffs());
		}
	}

	/**
	 * Show search result, or clear if none ({@link #searchResults} == null)
	 */
	private void showSearchResult() {
		
		if (searchResults == null) {
			for (int afterOffset = 0; afterOffset < afterActivity.getView().getVisibleImagesCount(); afterOffset++) {
				//no search result - clear any existing highlights
				afterActivity.setSearchResultHighlights(afterOffset, Collections.<IPagedCoordinateBox>emptyList());
			}
			
		} else {

			//search result is only on the "after" version
			final int page = afterActivity.getPage();
			afterActivity.getView().clearSearch();
			afterActivity.getView().clearCurrentSearch();
	
			for (int afterOffset = 0; afterOffset < afterActivity.getView().getVisibleImagesCount(); afterOffset++) {
				//prune search results to just the ones that overlap the current page
				List<IDocumentSearchResult> pageSearchResults = filterSearchResults(searchResults, page + afterOffset);

				//prune boxes to just the ones on the current page
				List<IPagedCoordinateBox> pageBoxes;
				{
					List<IPagedCoordinateBox> boxes = Lists.newArrayList(Iterables.concat(Iterables.transform(pageSearchResults, new Function<IDocumentSearchResult, Iterable<IPagedCoordinateBox>>() {
						@Override
						public Iterable<IPagedCoordinateBox> apply(IDocumentSearchResult result) {
							return result.getBoxes();
						}
					})));

					pageBoxes = filterBoxes(boxes, page + afterOffset);
				}
				afterActivity.setSearchResultHighlights(afterOffset, pageBoxes);
			}
		}
	}

	/**
	 * Show current search result ({@link #currentSearchResult}), or clear if none.
	 * 
	 * @param scroll scroll to result, changing pages first if necessary 
	 */
	private void showCurrentSearchResult(boolean scroll) {
		if (currentSearchResult == null) {
		
			//no current search result - clear any existing highlights
			afterActivity.setCurrentSearchResult(null, Collections.<IPagedCoordinateBox>emptyList());
		
		} else {

			//locate start of search result on both sides
			final CoordinateLocator afterLocator;
			final CoordinateLocator beforeLocator; //may be null if only showing one version
			{
				IPagedCoordinateBox afterBox = currentSearchResult.getBoxes().get(0);
				afterLocator = toCoordAtBoxCenter(afterBox);

				if (beforeSide == null) {
				
					//only showing one side
					beforeLocator = null;

				} else if(!diffsLoaded) {

					//no diff, or not loaded yet
					beforeLocator = afterLocator;

				} else {
					
					//promote locator to before side using diff
					beforeLocator = beforePromoter.apply(afterLocator);
				}
			}

			//first change pages if desired and necessary, then figure out
			//where to scroll to, or null for no scroll
			final Coordinate beforeScrollTo, afterScrollTo;
			boolean changeAfterPage = false;
			boolean changeBeforePage = false;
			if (scroll) {
				afterScrollTo = new Coordinate(afterLocator);
				beforeScrollTo = beforeLocator == null ? null : new Coordinate(beforeLocator);
				
				changeAfterPage = (afterActivity.getPage() + afterActivity.getView().getOffsetPage()) != afterLocator.getPage();
				if (changeAfterPage) {
					changePage(afterLocator.getPage(), afterSide, result -> {
						//only highlight result on "after" side, and only boxes on the current page
						//(search result may span pages)
						deferredAfterScrollTo = afterScrollTo;
					});
				}
				changeBeforePage = beforeActivity != null && (beforeActivity.getPage() + beforeActivity.getView().getOffsetPage()) != beforeLocator.getPage();
				if (changeBeforePage) {
					changePage(beforeLocator.getPage(), beforeSide, result -> beforeActivity.setCurrentSearchResult(beforeScrollTo, Collections.emptyList()));
				}
			} else {
				afterScrollTo = null;
				beforeScrollTo = null;
			}
			
			// if we didn't change page before, we need to apply the highlights now
			if (!changeAfterPage) {
				//only highlight result on "after" side, and only boxes on the current page
				//(search result may span pages)
				eventBus.fireEvent(new SearchReadyEvent(true, false));
				afterActivity.setCurrentSearchResult(afterScrollTo, filterBoxes(currentSearchResult.getBoxes(), afterActivity.getPage() + afterActivity.getView().getOffsetPage()));
			}
			if (!changeBeforePage)
			    if (beforeActivity != null) {
					beforeActivity.setCurrentSearchResult(beforeScrollTo, Collections.emptyList());
				} else {
					deferredBeforeScrollTo = beforeScrollTo;
				}
		}
	}

	/**
	 * Change to a different page and calculate offset
	 * 
	 * @param page
	 * @param diffSide
	 */
	void changePage(int page, final DiffSide diffSide) {
		changePage(page, diffSide, null);
	}

	/**
	 * Change to a different page and calculate offset
	 * 
	 * @param page
	 * @param diffSide
	 * @param executeAfterChange
	 */
	void changePage(int page, final DiffSide diffSide, final SuccessCallback<Void> executeAfterChange) {
		final int pages = diffSide == afterSide ? model.getVersion().getNumPages() : model.getBeforeVersion().getNumPages();
		final DocumentDiffSideActivity sideActivity = diffSide == afterSide ? afterActivity : beforeActivity;
		int imageCountOnPage = sideActivity.getView().getVisibleImagesCount();

		int offsetPage = imageCountOnPage - Math.min(pages - page + 1, imageCountOnPage);
		sideActivity.getView().setOffsetPage(offsetPage);

		//change base page according offset
		page -= offsetPage;

		//If images is not ready, we can't calculate images heights and scroll position
		//But it can happened if user click to next, previous very fast, and last click was handled correctly
		if (sideActivity.getView().isImagesReady()) {
			changePageAndExecute(page, offsetPage, diffSide, new Coordinate(0, sideActivity.getView().getIncrementalHeights().get(offsetPage)), executeAfterChange);
		}
	}

	/**
	 * Change to a different page and execute the callback after the page change is complete
	 * 
	 * Don't use directly, instead use({@link #changePage(int, DiffSide, SuccessCallback)})
	 * 
	 * @param page
	 * @param diffSide
	 * @param coord coordinates to scroll after changing page
	 * @param executeAfterChange callback to execute after changing page @null
	 */
	private void changePageAndExecute(int page, int offsetPage, final DiffSide diffSide, final Coordinate coord,
									  final SuccessCallback<Void> executeAfterChange) {
	    final DocumentDiffSideActivity sideActivity = diffSide == afterSide ? afterActivity : beforeActivity;

	    // We may be showing only one version
	    if (sideActivity == null)
	        return;
	    
	    // cannot change page right now
	    if (!sideActivity.isNavigationEnabled())
	    	return;
	    
	    final int pages = diffSide == afterSide ? model.getVersion().getNumPages() : model.getBeforeVersion().getNumPages();

	    //clip page (note page is 1-based)
	    page = Math.max(page, 1);
        page = Math.min(page, pages);

        final int clippedPage = page;
        
        if (diffSide == afterSide)
            afterPage = clippedPage;
        else
        	beforePage = clippedPage;
        
        // this disables navigation
        if (diffSide == afterSide)
            stopAfterSide();
        else
        	stopBeforeSide();

		deferredUpdateSearchResult = (diffSide == afterSide);
		
		view.setPaginationEnabled(diffSide, true);
		setPage(diffSide, clippedPage, offsetPage, pages, coord, executeAfterChange);
	}

	private void setPage(DiffSide diffSide, int clippedPage, int offsetPage, int pages, Coordinate coord, SuccessCallback<Void> executeAfterChange) {
		view.setPage(diffSide, clippedPage + offsetPage, pages);
		if (diffSide == afterSide) {
			startAfterSide();
			initDiffPanel(afterActivity, clippedPage, model.getVersion().getNumPages());
			
			if (coord != null) {
				afterActivity.getView().scrollTo(coord.getX(), coord.getY());
			}
		} else {
			startBeforeSide();
			initDiffPanel(beforeActivity, clippedPage, model.getBeforeVersion().getNumPages());
			
			if (coord != null) {
				beforeActivity.getView().scrollTo(coord.getX(), coord.getY());
			}
		}
		
		processMouseToolType( mouseToolType, isLock, getDisplayOrder());

		// re-enables controls
		view.setPaginationEnabled(diffSide, true);

		// execute callback
		if (executeAfterChange != null) {
			executeAfterChange.onSuccess(null);
		}
	}

	/**
	 * init document work panel that contains page images, and overlay panels(diff, search, etc.)
	 * visible document pages less than total page or equals default value
	 *
	 * @param sideActivity
	 * @param clippedPage - current page
	 * @param numPages total page count
	 */
	public void initDiffPanel(DocumentDiffSideActivity sideActivity, int clippedPage, int numPages) {
		sideActivity.getView().initDiffPanel(Math.min(numPages, MultipageUtil.DEFAULT_VISIBLE_IMAGE_COUNT));
		sideActivity.getView().setNumPages(numPages);
		sideActivity.updateImagesContent(clippedPage, numPages);
	}

	public boolean showPrevPage(DiffSide diffSide) {
		return showPage(-1, diffSide);
	}

	public void showPrevPage(DiffSide diffSide, Coordinate coord) {
		DocumentDiffSideActivity sideActivity = diffSide == afterSide ? afterActivity : beforeActivity;
		sideActivity.getView().setDeferredScrollToPrevious(true);
		changePageAndExecute(sideActivity.getPage() - 1, sideActivity.getView().getOffsetPage(), diffSide, coord, null);
	}

	public void showNextPage(DiffSide diffSide, Coordinate coord) {
		DocumentDiffSideActivity sideActivity = diffSide == afterSide ? afterActivity : beforeActivity;
		sideActivity.getView().setDeferredScrollToNext(true);
		changePageAndExecute(sideActivity.getPage() + 1, Math.max(0, sideActivity.getView().getOffsetPage() - 1), diffSide, coord, null);
	}

	public boolean showNextPage(DiffSide diffSide) {
		return showPage(1, diffSide);
	}

	public boolean showPage(int offset, DiffSide diffSide) {
		DocumentDiffSideActivity sideActivity = diffSide == afterSide ? afterActivity : beforeActivity;
		if (sideActivity == null)
			return false;
		changePage(sideActivity.getPage() + sideActivity.getView().getOffsetPage() + offset, diffSide, null);
		return true;
	}

	@Override
	protected DocumentDiffSideModel createSideModel(IVersion version) {

		IVersion afterVersion = model.getVersion();

		final int page;
		if (afterVersion.equals(version))
	        page = afterPage;
	    else
	        page = beforePage;

	    return new DocumentDiffSideModel(model, version, afterVersion, page);
	}

	@Override
	protected DocumentDiffSideActivity createSideActivity(IDocumentDiffSideView view, DiffSide side) {
		DocumentDiffSideActivity activity = sideFactory.create(view);
		activity.setListenToExternalSelectEvents(false); // always pass down events
		if (side == afterSide) {
			activity.setPromoter(new CoordinatePromoter(afterPromoter));
		}
		else {
			activity.setPromoter(new CoordinatePromoter(beforePromoter));
			activity.setOtherSidePromoter(new CoordinatePromoter(afterPromoter));
		}
		return activity;
	}

	@Override
    public void initDiffState(AbstractDiffActivity<?, ?, ?> previousDiffActivity) {
    	super.initDiffState(previousDiffActivity);
    	
    	if (previousDiffActivity instanceof DocumentDiffActivity) {
    		//page number is not persisted permanently, but is retained when switching versions
    		DocumentDiffActivity previousDocDiffActivity = (DocumentDiffActivity)previousDiffActivity;
    		
	        int newAfterPage  = previousDocDiffActivity.afterPage;
			int newBeforePage = previousDocDiffActivity.beforePage;

			//Check that newAfterPage page is within model.getVersion() pages
			if (model.getVersion().getNumPages() != null && model.getVersion().getNumPages() < newAfterPage) {
				newAfterPage = model.getVersion().getNumPages();
			}

			//Check that newBeforePage page is within model.getBeforeVersion() pages
			if (model.getBeforeVersion().getNumPages() != null && model.getBeforeVersion().getNumPages() < newBeforePage) {
				newBeforePage = model.getBeforeVersion().getNumPages();
			}

			if (beforeSide == null) {
				//special case - if we're only showing a single version and we were previously
				//showing that version, use the page number from the side which corresponded 
				//to that version in the previous diff activity
				if (model.getVersion().equals(previousDocDiffActivity.beforeVersionOnStop))
					newAfterPage = previousDocDiffActivity.beforePage;
			}
			
			// reload diffs
			runRefresh(model.getVersion(), model.getBeforeVersion(), newAfterPage, newBeforePage);
    	}
    }
	
	/**
	 * Filter search results by page.
	 * 
	 * @param searchResults sorted search results @nonnull
	 * @param page page to filter
	 * @return search results with boxes on page @nonnull
	 */
	static List<IDocumentSearchResult> filterSearchResults(List<IDocumentSearchResult> searchResults, int page) {

		List<IDocumentSearchResult> pageSearchResults = searchResults;
		
		//prune results that end before page
		pageSearchResults = SortedLists.subListGreaterThan(
			pageSearchResults, 
			new DocumentSearchResult(
				ArrayLists.<IPagedCoordinateBox>singletonArrayList(
					new DMPagedCoordinateBox(page, 0, 0, 0, 0, 0)
					)
				),
			Ordering.from(BOX_DISPLAY_ORDER).onResultOf(new Function<IDocumentSearchResult, IPagedCoordinateBox>() {
					@Override
					public IPagedCoordinateBox apply(@Nonnull IDocumentSearchResult searchResult) {
						//compare page of last box in result
						List<IPagedCoordinateBox> boxes = searchResult.getBoxes();
						return boxes.get(boxes.size()-1);
					}
				})
			);
		
		//prune results that start after page
		pageSearchResults = SortedLists.subListLessThan(
			pageSearchResults, 
			new DocumentSearchResult(
				ArrayLists.<IPagedCoordinateBox>singletonArrayList(
					new DMPagedCoordinateBox(page+1, 0, 0, 0, 0, 0)
					)
				),
			Ordering.from(BOX_DISPLAY_ORDER).onResultOf(new Function<IDocumentSearchResult, IPagedCoordinateBox>() {
					@Override
					public IPagedCoordinateBox apply(@Nonnull IDocumentSearchResult searchResult) {
						//compare page of first box in result
						return searchResult.getBoxes().get(0);
					}
				})
			);
		
		return pageSearchResults;
	}
	
	public void updateLoadingPage(DiffSide side, int page) {
		view.setLoadingPage(side, page);
	}

	public void hideLoadingPage(DiffSide side) {
		view.hideLoadingPage(side);
	}

	private class RefreshLoadingPage implements AsyncRunnable<Boolean> {

		private ModelRefreshManager<Integer> refreshManagerRunnable;
		private DiffSide side;
		private int versionId;
		private int reviewId;
		private int afterPage;
		private int beforePage;
		private int afterVersionId;
		private int beforeVersionId;
		private boolean first = true;

		public RefreshLoadingPage(DiffSide side, int versionId, int reviewId, int afterVersionId, int beforeVersionId, int afterPage, int beforePage) {
			this.side = side;
			this.versionId = versionId;
			this.reviewId = reviewId;
			this.afterPage = afterPage;
			this.beforePage = beforePage;
			this.afterVersionId = afterVersionId;
			this.beforeVersionId = beforeVersionId;
		}

		public void run(final AsyncCallback<Boolean> callback) {
			api.getProgressPage(reviewId, versionId, refreshManagerRunnable.ifActive(new AsyncCallback<Integer>() {
				@Override
				public void onFailure(Throwable caught) {
					callback.onSuccess(false);
					DocumentDiffActivity.this.hideLoadingPage(side);
				}

				@Override
				public void onSuccess(Integer result) {
					if (first && model.getVersion().getId() == model.getBeforeVersion().getId()) {
						first = false;
						locatorSelectedHandler.onLocatorSelected(new LocatorSelectedEvent(context.getModel().getSelectedLocator()));
					}
					if (result != null) {
							DocumentDiffActivity.this.updateLoadingPage(side, result);
							refreshManagerRunnable.setRefreshInterval(REFRESH_PROGRESS_INTERVAL_MILLIS);
						    refreshManagerRunnable.setModel(result);
							callback.onSuccess(true);
						} else {
							callback.onSuccess(false);
						    DocumentDiffActivity.this.hideLoadingPage(side);
						}
				}}
			));
		}

		public ModelRefreshManager<Integer> getRefreshManagerRunnable() {
			return refreshManagerRunnable;
		}

		public void setRefreshManagerRunnable(ModelRefreshManager<Integer> refreshManagerRunnable) {
			this.refreshManagerRunnable = refreshManagerRunnable;
		}
	}


	/**
	 * Filter boxes by page.
	 * 
	 * @param sortedBoxes sorted boxes @nonnull
	 * @param page page to filter
	 * @return boxes on page @nonnull
	 */
	static List<IPagedCoordinateBox> filterBoxes(List<IPagedCoordinateBox> sortedBoxes, int page) {
		return SortedLists.subList(
			sortedBoxes, 
			new DMPagedCoordinateBox(page, 0, 0, 0, 0, 0),
			new DMPagedCoordinateBox(page+1, 0, 0, 0, 0, 0),
			BOX_DISPLAY_ORDER
			);
	}
	
	static final private class CoordinatePromoter implements Function<ILocator, ILocator> {
		final private Function<CoordinateLocator, CoordinateLocator> inner;

		private CoordinatePromoter(Function<CoordinateLocator, CoordinateLocator> inner) {
			this.inner = inner;
		}
		
		@Override
		public ILocator apply(ILocator input) {
			if (input instanceof CoordinateLocator)
				return inner.apply((CoordinateLocator)input);
			else
				return input;
		}
		
		
	}
	
}
