/**
 * Session, preference and account packets sent by the client.
 */
package org.darkan.core.net.prot.packet

import org.darkan.core.model.Preference
import org.darkan.core.net.prot.ClientProt

// Game State
@JvmInline value class RegionLoadedConfirm(val dummy: Int = 0) : ClientProt

@JvmInline value class SoundEffectMusicEnded(val musicId: Int) : ClientProt

@JvmInline value class SongLoaded(val songId: Int) : ClientProt

@JvmInline value class CutsceneFinished(val forced: Boolean) : ClientProt

@JvmInline value class WritePing(val ping: Int) : ClientProt

@JvmInline value class SendPreferences(val preferences: Map<Preference, Int>) : ClientProt

@JvmInline value class TransmitvarVerifyId(val id: Int) : ClientProt

@JvmInline value class RequestWorldList(val worldlistVersion: Int) : ClientProt

data class BugReport(val category: Int, val body: String, val reproSteps: String) : ClientProt

// Account/Login
@JvmInline value class EmailValidationSubmitCode(val code: String) : ClientProt

data class EmailValidationAddNewAddress(val email: String, val flags: Int) : ClientProt

data class EmailValidationChangeAddress(val email: String, val email2: String) : ClientProt

@JvmInline value class CheckEmailValidity(val encryptedData: ByteArray) : ClientProt

@JvmInline value class SendSignUpForm(val encryptedData: ByteArray) : ClientProt

@JvmInline value class AccountCreationStage(val stage: Int) : ClientProt

data class LobbyHyperlink(val service: String, val page: String, val query: String, val flags: Int) : ClientProt

// Unknown/Misc
@JvmInline value class PlayVorbis(val fileId: Int) : ClientProt
