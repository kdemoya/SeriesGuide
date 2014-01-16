/*
 * Copyright 2014 Uwe Trottmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.battlelancer.seriesguide.util;

import com.battlelancer.seriesguide.settings.TraktCredentials;
import com.jakewharton.trakt.Trakt;
import com.jakewharton.trakt.entities.Movie;
import com.jakewharton.trakt.enumerations.Extended;
import com.jakewharton.trakt.enumerations.Extended2;
import com.jakewharton.trakt.services.MovieService;
import com.jakewharton.trakt.services.UserService;
import com.uwetrottmann.androidutils.AndroidUtils;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import retrofit.RetrofitError;

import static com.battlelancer.seriesguide.provider.SeriesGuideContract.Movies;
import static com.battlelancer.seriesguide.sync.SgSyncAdapter.UpdateResult;

public class MovieTools {

    public static void addToWatchlist(Context context, int movieTmdbId) {
        if (TraktCredentials.get(context).hasCredentials()) {
            if (!Utils.isConnected(context, true)) {
                return;
            }
            AndroidUtils.executeAsyncTask(
                    new TraktTask(context, null).watchlistMovie(movieTmdbId)
            );
        }

        // make modifications to local database
        Boolean movieExists = isMovieExists(context, movieTmdbId);
        if (movieExists == null) {
            return;
        }
        if (movieExists) {
            updateMovie(context, movieTmdbId, Movies.IN_WATCHLIST, true);
        } else {
            addMovieAsync(context, movieTmdbId, AddMovieTask.AddTo.WATCHLIST);
        }
    }

    public static void removeFromWatchlist(Context context, int movieTmdbId) {
        if (TraktCredentials.get(context).hasCredentials()) {
            if (!Utils.isConnected(context, true)) {
                return;
            }
            AndroidUtils.executeAsyncTask(
                    new TraktTask(context, null).unwatchlistMovie(movieTmdbId)
            );
        }

        Boolean isInCollection = isMovieInCollection(context, movieTmdbId);
        if (isInCollection == null) {
            return;
        }
        if (isInCollection) {
            // just update watchlist flag
            updateMovie(context, movieTmdbId, Movies.IN_WATCHLIST, false);
        } else {
            // remove from database
            deleteMovie(context, movieTmdbId);
        }
    }

    private static void addMovieAsync(Context context, int movieTmdbId, AddMovieTask.AddTo addTo) {
        new AddMovieTask(context, addTo).execute(movieTmdbId);
    }

    private static ContentValues[] buildMoviesContentValues(List<Movie> movies) {
        ContentValues[] valuesArray = new ContentValues[movies.size()];
        int index = 0;
        for (Movie movie : movies) {
            valuesArray[index] = buildMovieContentValues(movie);
            index++;
        }
        return valuesArray;
    }

    private static ContentValues buildMovieContentValues(Movie movie) {
        ContentValues values = buildBasicMovieContentValues(movie);

        values.put(Movies.IN_COLLECTION, convertBooleanToInt(movie.inCollection));
        values.put(Movies.IN_WATCHLIST, convertBooleanToInt(movie.inWatchlist));

        return values;
    }

    /**
     * Extracts basic properties, except in_watchlist and in_collection.
     */
    private static ContentValues buildBasicMovieContentValues(Movie movie) {
        ContentValues values = new ContentValues();

        values.put(Movies.TMDB_ID, movie.tmdbId);
        values.put(Movies.TITLE, movie.title);
        values.put(Movies.RELEASED_UTC_MS, movie.released.getTime());
        values.put(Movies.WATCHED, convertBooleanToInt(movie.watched));
        values.put(Movies.POSTER, movie.images == null ? "" : movie.images.poster);

        return values;
    }

    private static int convertBooleanToInt(Boolean value) {
        if (value == null) {
            return 0;
        }
        return value ? 1 : 0;
    }

    private static void deleteMovie(Context context, int movieTmdbId) {
        context.getContentResolver().delete(Movies.buildMovieUri(movieTmdbId), null, null);
    }

    /**
     * Returns a set of the TMDb ids of all movies in the local database.
     *
     * @return null if there was an error, empty list if there are no movies.
     */
    private static HashSet<Integer> getMovieTmdbIdsAsSet(Context context) {
        HashSet<Integer> localMoviesIds = new HashSet<>();

        Cursor movies = context.getContentResolver().query(Movies.CONTENT_URI,
                new String[]{Movies._ID, Movies.TMDB_ID}, null, null, null);
        if (movies == null) {
            return null;
        }

        while (movies.moveToNext()) {
            localMoviesIds.add(movies.getInt(1));
        }

        movies.close();

        return localMoviesIds;
    }

    private static Boolean isMovieInCollection(Context context, int movieTmdbId) {
        Cursor movie = context.getContentResolver().query(Movies.buildMovieUri(movieTmdbId),
                new String[]{Movies.IN_COLLECTION}, null, null, null);
        if (movie == null || !movie.moveToFirst()) {
            return null;
        }

        boolean isInCollection = movie.getInt(0) == 1;

        movie.close();

        return isInCollection;
    }

    private static Boolean isMovieExists(Context context, int movieTmdbId) {
        Cursor movie = context.getContentResolver().query(Movies.CONTENT_URI, new String[]{
                Movies._ID}, Movies.TMDB_ID + "=" + movieTmdbId, null, null);
        if (movie == null) {
            return null;
        }

        boolean movieExists = movie.getCount() > 0;

        movie.close();

        return movieExists;
    }

    private static void updateMovie(Context context, int movieTmdbId, String column,
            boolean value) {
        ContentValues values = new ContentValues();
        values.put(column, value);
        context.getContentResolver().update(Movies.buildMovieUri(movieTmdbId), values, null, null);
    }

    private static class AddMovieTask extends AsyncTask<Integer, Void, Void> {

        private final Context mContext;

        private final AddTo mAddTo;

        public enum AddTo {
            COLLECTION,
            WATCHLIST
        }

        public AddMovieTask(Context context, AddTo addTo) {
            mContext = context;
            mAddTo = addTo;
        }

        @Override
        protected Void doInBackground(Integer... params) {
            int movieTmdbId = params[0];

            // get summary from trakt
            Trakt trakt = ServiceUtils.getTraktWithAuth(mContext);
            if (trakt == null) {
                // fall back
                trakt = ServiceUtils.getTrakt(mContext);
            }

            Movie movie = trakt.movieService().summary(movieTmdbId);

            // store in database
            ContentValues values = buildBasicMovieContentValues(movie);
            values.put(Movies.IN_COLLECTION, mAddTo == AddTo.COLLECTION ?
                    1 : convertBooleanToInt(movie.inCollection));
            values.put(Movies.IN_WATCHLIST, mAddTo == AddTo.WATCHLIST ?
                    1 : convertBooleanToInt(movie.inWatchlist));

            mContext.getContentResolver().insert(Movies.CONTENT_URI, values);

            return null;
        }

    }

    public static class Download {

        /**
         * Updates the movie local database against trakt movie watchlist and collection, therefore
         * adds, updates and removes movies in the database.<br/>Performs <b>synchronous network
         * access</b>, so make sure to run this on a background thread!
         */
        public static UpdateResult syncMoviesFromTrakt(Context context) {
            Trakt trakt = ServiceUtils.getTraktWithAuth(context);
            if (trakt == null) {
                // trakt is not connected, we are done here
                return UpdateResult.SUCCESS;
            }
            UserService userService = trakt.userService();

            // return if connectivity is lost
            if (!AndroidUtils.isNetworkConnected(context)) {
                return UpdateResult.INCOMPLETE;
            }

            HashSet<Integer> localMovies = getMovieTmdbIdsAsSet(context);
            HashSet<Integer> moviesToRemove = new HashSet<>(localMovies);
            HashSet<Integer> moviesToAdd = new HashSet<>();
            ArrayList<ContentProviderOperation> batch = new ArrayList<>();

            // get trakt watchlist
            List<Movie> watchlistMovies;
            try {
                watchlistMovies = userService
                        .watchlistMovies(TraktCredentials.get(context).getUsername());
            } catch (RetrofitError e) {
                return UpdateResult.INCOMPLETE;
            }

            // build watchlist updates
            ContentValues values = new ContentValues();
            values.put(Movies.IN_WATCHLIST, true);
            buildMovieUpdateOps(watchlistMovies, localMovies, moviesToAdd, moviesToRemove, batch,
                    values);

            // apply watchlist updates
            DBUtils.applyInSmallBatches(context, batch);
            batch.clear();
            values.clear();

            // return if connectivity is lost
            if (!AndroidUtils.isNetworkConnected(context)) {
                return UpdateResult.INCOMPLETE;
            }

            // get trakt collection
            List<Movie> collectionMovies;
            try {
                collectionMovies = userService.libraryMoviesCollection(
                        TraktCredentials.get(context).getUsername(), Extended.MIN);
            } catch (RetrofitError e) {
                return UpdateResult.INCOMPLETE;
            }

            // build collection updates
            values.put(Movies.IN_COLLECTION, true);
            buildMovieUpdateOps(collectionMovies, localMovies, moviesToAdd, moviesToRemove, batch,
                    values);

            // apply collection updates
            DBUtils.applyInSmallBatches(context, batch);
            batch.clear();

            // remove movies not on trakt
            buildMovieDeleteOps(moviesToRemove, batch);
            DBUtils.applyInSmallBatches(context, batch);

            // return if connectivity is lost
            if (!AndroidUtils.isNetworkConnected(context)) {
                return UpdateResult.INCOMPLETE;
            }

            // add movies new from trakt
            return addMovies(context, trakt, moviesToAdd.toArray(new Integer[moviesToAdd.size()]));
        }

        /**
         * Downloads movie summaries from trakt and adds them to the database.
         */
        private static UpdateResult addMovies(Context context, Trakt trakt,
                Integer... movieTmdbIds) {
            MovieService movieService = trakt.movieService();
            StringBuilder tmdbIds = new StringBuilder();

            for (int i = 0; i < movieTmdbIds.length; i++) {
                if (tmdbIds.length() != 0) {
                    // separate with commas
                    tmdbIds.append(",");
                }
                tmdbIds.append(movieTmdbIds[i]);

                // process in batches of 10 or less
                if (i % 10 == 0 || i == movieTmdbIds.length - 1) {
                    // get summaries from trakt
                    List<Movie> movies;
                    try {
                        movies = movieService.summaries(tmdbIds.toString(), Extended2.FULL);
                    } catch (RetrofitError e) {
                        return UpdateResult.INCOMPLETE;
                    }

                    // insert into database
                    context.getContentResolver()
                            .bulkInsert(Movies.CONTENT_URI, buildMoviesContentValues(movies));

                    // reset
                    tmdbIds = new StringBuilder();
                }
            }

            return UpdateResult.SUCCESS;
        }

        private static void buildMovieUpdateOps(List<Movie> remoteMovies,
                HashSet<Integer> localMovies, HashSet<Integer> moviesToAdd,
                HashSet<Integer> moviesToRemove, ArrayList<ContentProviderOperation> batch,
                ContentValues values) {
            for (Movie movie : remoteMovies) {
                if (localMovies.contains(movie.tmdbId)) {
                    // update existing movie
                    ContentProviderOperation op = ContentProviderOperation
                            .newUpdate(Movies.buildMovieUri(movie.tmdbId))
                            .withValues(values).build();
                    batch.add(op);

                    // prevent movie from getting removed
                    moviesToRemove.remove(movie.tmdbId);
                } else {
                    // insert new movie
                    moviesToAdd.add(movie.tmdbId);
                }
            }
        }

        private static void buildMovieDeleteOps(HashSet<Integer> moviesToRemove,
                ArrayList<ContentProviderOperation> batch) {
            for (Integer movieTmdbId : moviesToRemove) {
                ContentProviderOperation op = ContentProviderOperation
                        .newDelete(Movies.buildMovieUri(movieTmdbId)).build();
                batch.add(op);
            }
        }

    }

}
