package eu.kanade.mangafeed.presenter;

import android.os.Bundle;

import java.util.List;

import javax.inject.Inject;

import de.greenrobot.event.EventBus;
import eu.kanade.mangafeed.data.helpers.DatabaseHelper;
import eu.kanade.mangafeed.data.helpers.PreferencesHelper;
import eu.kanade.mangafeed.data.models.Chapter;
import eu.kanade.mangafeed.data.models.Page;
import eu.kanade.mangafeed.events.SourceChapterEvent;
import eu.kanade.mangafeed.sources.base.Source;
import eu.kanade.mangafeed.ui.activity.ReaderActivity;
import eu.kanade.mangafeed.util.EventBusHook;
import icepick.State;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import timber.log.Timber;

public class ReaderPresenter extends BasePresenter<ReaderActivity> {

    @Inject PreferencesHelper prefs;
    @Inject DatabaseHelper db;

    private Source source;
    private Chapter chapter;
    private List<Page> pageList;
    private boolean initialStart = true;
    @State int currentPage = 0;

    private static final int GET_PAGE_LIST = 1;
    private static final int GET_PAGE_IMAGES = 2;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        restartableLatestCache(GET_PAGE_LIST,
                () -> getPageListObservable()
                        .doOnNext(pages -> pageList = pages)
                        .doOnCompleted(() -> start(GET_PAGE_IMAGES)),
                (view, pages) -> {
                    view.onPageListReady(pages);
                    if (initialStart && !chapter.read)
                        view.setSelectedPage(chapter.last_page_read - 1);
                    else if (currentPage != 0)
                        view.setSelectedPage(currentPage);
                },
                (view, error) -> Timber.e("An error occurred while downloading page list")
        );

        restartableReplay(GET_PAGE_IMAGES,
                this::getPageImagesObservable,
                (view, page) -> {
                },
                (view, error) -> Timber.e("An error occurred while downloading an image"));
    }

    @Override
    protected void onTakeView(ReaderActivity view) {
        super.onTakeView(view);
        registerForStickyEvents();

        if (prefs.hideStatusBarSet()) {
            view.hideStatusBar();
        }
    }

    @Override
    protected void onDropView() {
        unregisterForEvents();
        initialStart = false;
        super.onDropView();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().removeStickyEvent(SourceChapterEvent.class);
        source.savePageList(chapter.url, pageList);
        saveChapter();
        super.onDestroy();
    }

    @EventBusHook
    public void onEventMainThread(SourceChapterEvent event) {
        if (source == null || chapter == null) {
            source = event.getSource();
            chapter = event.getChapter();

            start(1);
        }
    }

    private Observable<List<Page>> getPageListObservable() {
        return source.pullPageListFromNetwork(chapter.url)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private Observable<Page> getPageImagesObservable() {
        return Observable.merge(
                    Observable.from(pageList).filter(page -> page.getImageUrl() != null),
                    source.getRemainingImageUrlsFromPageList(pageList)
                )
                .flatMap(source::getCachedImage)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public void setCurrentPage(int currentPage) {
        this.currentPage = currentPage;
    }

    private void saveChapter() {
        chapter.last_page_read = currentPage + 1;
        if (currentPage == pageList.size() - 1) {
            chapter.read = true;

        }
        db.insertChapterBlock(chapter);
    }
}
