package br.geomaps

import android.app.Application
import br.geomaps.data.Backup
import br.geomaps.data.Db
import br.geomaps.data.IconFactory
import br.geomaps.data.MediaStore
import br.geomaps.data.Repo
import br.geomaps.data.SettingsStore
import br.geomaps.location.Compass
import br.geomaps.location.LocationHub
import br.geomaps.location.TrackRecorder
import br.geomaps.map.DemManager
import br.geomaps.map.OfflineStore
import org.maplibre.android.MapLibre

class GeoMapsApp : Application() {
    lateinit var settings: SettingsStore
    lateinit var db: Db
    lateinit var media: MediaStore
    lateinit var icons: IconFactory
    lateinit var repo: Repo
    lateinit var locationHub: LocationHub
    lateinit var compass: Compass
    lateinit var recorder: TrackRecorder
    lateinit var dem: DemManager
    lateinit var offline: OfflineStore

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        runCatching { br.geomaps.map.Glyphs.ensure(this) }
        settings = SettingsStore(this)
        db = Db(this)
        media = MediaStore(this)
        icons = IconFactory(this, media)
        repo = Repo(this, db, media, icons)
        locationHub = LocationHub(this, settings)
        compass = Compass(this)
        recorder = TrackRecorder(db).also { it.maxAccuracy = settings.value.recMaxAccuracyM.toFloat() }
        dem = DemManager(this, settings)
        offline = OfflineStore(this, db, settings, dem)
        offline.configureAmbientCache()
        runCatching { Backup.schedule(this, settings.value.autoBackup) }
    }
}
