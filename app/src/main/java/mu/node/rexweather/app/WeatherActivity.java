package mu.node.rexweather.app;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;
import mu.node.rexweather.app.Helpers.DayFormatter;
import mu.node.rexweather.app.Helpers.TemperatureFormatter;
import mu.node.rexweather.app.Models.CurrentWeather;
import mu.node.rexweather.app.Models.WeatherForecast;
import mu.node.rexweather.app.services.LocationService;
import mu.node.rexweather.app.services.LocationUnavailableException;
import mu.node.rexweather.app.services.WeatherService;
import retrofit.RetrofitError;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

/**
 * Weather Activity.
 *
 * This is the main activity for our app. It simply serves as a container for the Weather Fragment.
 * We prefer to build our implementation in a fragment because that enables future reuse if, for
 * example we build a tablet version of this app.
 */
public class WeatherActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weather);

        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.container, new WeatherFragment())
                    .commit();
        }
    }

    /**
     * Weather Fragment.
     *
     * Displays the current weather as well as a 7 day forecast for our location. Data is loaded
     * from a web service.
     */
    public static class WeatherFragment extends Fragment {

        private static final String KEY_CURRENT_WEATHER = "key_current_weather";
        private static final String KEY_WEATHER_FORECASTS = "key_weather_forecasts";

        private final CompositeSubscription mCompositeSubscription = new CompositeSubscription();
        private SwipeRefreshLayout mSwipeRefreshLayout;
        private TextView mLocationNameTextView;
        private TextView mCurrentTemperatureTextView;
        private ListView mForecastListView;
        private TextView mAttributionTextView;

        @Override
        public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                final Bundle savedInstanceState) {

            final View rootView = inflater.inflate(R.layout.fragment_weather, container, false);
            mLocationNameTextView = (TextView) rootView.findViewById(R.id.location_name);
            mCurrentTemperatureTextView = (TextView) rootView
                    .findViewById(R.id.current_temperature);

            // Set up list view for weather forecasts.
            mForecastListView = (ListView) rootView.findViewById(R.id.weather_forecast_list);
            final WeatherForecastListAdapter adapter = new WeatherForecastListAdapter(
                    new ArrayList<WeatherForecast>());
            mForecastListView.setAdapter(adapter);

            mAttributionTextView = (TextView) rootView.findViewById(R.id.attribution);
            mAttributionTextView.setVisibility(View.INVISIBLE);

            // Set up swipe refresh layout.
            mSwipeRefreshLayout = (SwipeRefreshLayout) rootView
                    .findViewById(R.id.swipe_refresh_container);
            mSwipeRefreshLayout.setColorScheme(R.color.brand_main,
                    android.R.color.black,
                    R.color.brand_main,
                    android.R.color.black);
            mSwipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
                @Override
                public void onRefresh() {
                    updateWeather();
                }
            });

            updateWeather();
            return rootView;
        }

        @Override
        public void onDestroy() {
            mCompositeSubscription.unsubscribe();
            super.onDestroy();
        }

        /**
         * Provides items for our list view.
         */
        private class WeatherForecastListAdapter extends ArrayAdapter {
            private List<WeatherForecast> mWeatherForecasts;

            public WeatherForecastListAdapter(List<WeatherForecast> weatherForecasts) {
                super(getActivity(), 0, weatherForecasts);
                mWeatherForecasts = weatherForecasts;
            }

            public void updateForecasts(List<WeatherForecast> forecasts) {
                mWeatherForecasts = forecasts;
                notifyDataSetChanged();
            }

            @Override
            public int getCount() {
                return mWeatherForecasts.size();
            }

            @Override
            public WeatherForecast getItem(final int position) {
                return mWeatherForecasts.get(position);
            }

            @Override
            public long getItemId(final int position) {
                return position;
            }

            @Override
            public boolean isEnabled(int position) {
                return false;
            }

            @Override
            public View getView(final int position, View convertView, final ViewGroup parent) {
                ViewHolder viewHolder;

                if (convertView == null) {
                    convertView = getActivity().getLayoutInflater()
                            .inflate(R.layout.weather_forecast_list_item, null);

                    viewHolder = new ViewHolder();
                    viewHolder.dayTextView = (TextView) convertView.findViewById(R.id.day);
                    viewHolder.descriptionTextView = (TextView) convertView
                            .findViewById(R.id.description);
                    viewHolder.maximumTemperatureTextView = (TextView) convertView
                            .findViewById(R.id.maximum_temperature);
                    viewHolder.minimumTemperatureTextView = (TextView) convertView
                            .findViewById(R.id.minimum_temperature);
                    convertView.setTag(viewHolder);
                } else {
                    viewHolder = (ViewHolder) convertView.getTag();
                }

                WeatherForecast weatherForecast = getItem(position);

                DayFormatter dayFormatter = new DayFormatter(getActivity());
                final String day = dayFormatter.format(weatherForecast.getTimestamp());
                viewHolder.dayTextView.setText(day);
                viewHolder.descriptionTextView.setText(weatherForecast.getDescription());
                viewHolder.maximumTemperatureTextView.setText(
                        TemperatureFormatter.format(weatherForecast.getMaximumTemperature()));
                viewHolder.minimumTemperatureTextView.setText(
                        TemperatureFormatter.format(weatherForecast.getMinimumTemperature()));

                return convertView;
            }

            /**
             * Cache to avoid doing expensive findViewById() calls for each getView().
             */
            private class ViewHolder {
                TextView dayTextView;
                TextView descriptionTextView;
                TextView maximumTemperatureTextView;
                TextView minimumTemperatureTextView;
            }
        }

        /**
         * Get weather data for the current location and update the UI.
         */
        private void updateWeather() {
            mSwipeRefreshLayout.setRefreshing(true);

            final LocationManager locationManager = (LocationManager) getActivity()
                    .getSystemService(Context.LOCATION_SERVICE);
            final LocationService locationService = new LocationService(locationManager);

            // Get our current location.
            final Observable fetchDataObservable = locationService.getLocation()
            .flatMap(new Func1<Location, Observable<?>>() {
                @Override
                public Observable<?> call(Location location) {
                    final WeatherService weatherService = new WeatherService();
                    final double longitude = location.getLongitude();
                    final double latitude = location.getLatitude();

                    return Observable.zip(
                            // Fetch current and 7 day forecasts for the location.
                            weatherService.fetchCurrentWeather(longitude, latitude),
                            weatherService.fetchWeatherForecasts(longitude, latitude),

                            // Only handle the fetched results when both sets are available.
                            new Func2<CurrentWeather, List<WeatherForecast>, Object>() {
                                @Override
                                public Object call(final CurrentWeather currentWeather,
                                                   final List<WeatherForecast> weatherForecasts) {

                                    HashMap weatherData = new HashMap();
                                    weatherData.put(KEY_CURRENT_WEATHER, currentWeather);
                                    weatherData.put(KEY_WEATHER_FORECASTS, weatherForecasts);
                                    return weatherData;
                                }
                            }
                    );
                }
            });

            mCompositeSubscription.add(fetchDataObservable
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Subscriber<HashMap>() {
                        @Override
                        public void onCompleted() {
                            mSwipeRefreshLayout.setRefreshing(false);
                            mAttributionTextView.setVisibility(View.VISIBLE);
                        }

                        @Override
                        public void onError(final Throwable error) {
                            mSwipeRefreshLayout.setRefreshing(false);

                            if (error instanceof LocationUnavailableException) {
                                Crouton.makeText(getActivity(),
                                        R.string.error_location_unavailable, Style.ALERT).show();
                            } else if (error instanceof RetrofitError) {
                                Crouton.makeText(getActivity(),
                                        R.string.error_fetch_weather, Style.ALERT).show();
                            } else {
                                error.printStackTrace();
                                throw new RuntimeException("See inner exception");
                            }
                        }

                        @Override
                        public void onNext(final HashMap weatherData) {
                            // Update UI with current weather.
                            final CurrentWeather currentWeather = (CurrentWeather) weatherData
                                    .get(KEY_CURRENT_WEATHER);
                            mLocationNameTextView.setText(currentWeather.getLocationName());
                            mCurrentTemperatureTextView.setText(
                                    TemperatureFormatter.format(currentWeather.getTemperature()));

                            // Update weather forecast list.
                            final List<WeatherForecast> weatherForecasts = (List<WeatherForecast>)
                                    weatherData.get(KEY_WEATHER_FORECASTS);
                            ((WeatherForecastListAdapter) mForecastListView.getAdapter())
                                    .updateForecasts(weatherForecasts);
                        }
                    })
            );
        }
    }
}