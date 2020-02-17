package tech.ula.model.repositories

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import tech.ula.model.daos.AppsDao
import tech.ula.model.entities.App
import tech.ula.model.remote.RemoteAppsSource
import tech.ula.utils.asyncAwait

class AppsRepository(private val appsDao: AppsDao, private val remoteAppsSource: RemoteAppsSource) {

    private val refreshStatus = MutableLiveData<RefreshStatus>()

    fun getAllApps(): LiveData<List<App>> {
        return appsDao.getAllApps()
    }

    fun getAppByName(name: String): App {
        return appsDao.getAppByName(name)
    }

    suspend fun refreshData() {
        asyncAwait {
            refreshStatus.postValue(RefreshStatus.ACTIVE)
            try {
                remoteAppsSource.fetchAppsList().forEach {
                    appsDao.insertApp(it)
                }
            } catch (err: Exception) {
                refreshStatus.postValue(RefreshStatus.FAILED)
                return@asyncAwait
            }
            refreshStatus.postValue(RefreshStatus.FINISHED)
        }
    }

    fun getRefreshStatus(): LiveData<RefreshStatus> {
        return refreshStatus
    }
}

enum class RefreshStatus {
    ACTIVE, FINISHED, FAILED
}