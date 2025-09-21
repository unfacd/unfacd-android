package org.whispersystems.signalservice.api.groupsv2

import androidx.annotation.WorkerThread
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.signal.core.util.logging.Log

//AA+
data class FenceInfo(val jsonModel: JsonModel) {

  data class JsonModel(
    @JsonProperty("fid") val fid: Long,
    @JsonProperty("eid") val eid: Int,
    @JsonProperty("cname") val cname: String,
    @JsonProperty("dname") val dname: String,
    @JsonProperty("when_created") val whenCreated: Long,
    @JsonProperty("members_count") val membersCount: Int,
    @JsonProperty("avatar") val avatar: String,
    @JsonProperty("fence_permission_membership") val groupMembershipPermission: Boolean
  )

  companion object {
    private val TAG = Log.tag(FenceInfo::class.java)

    private val objectMapper = ObjectMapper().registerKotlinModule()

    @WorkerThread
    @JvmStatic
    fun fromRestApi(json: String): FenceInfo? {
      return FenceInfo(json.let { objectMapper.readValue(it, JsonModel::class.java) })
    }
  }
}