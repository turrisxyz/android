/**
 * ownCloud Android client application
 *
 * @author David A. Velasco
 * @author David González Verdugo
 * @author Christian Schabesberger
 * Copyright (C) 2020 ownCloud GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http:></http:>//www.gnu.org/licenses/>.
 */

package com.owncloud.android.presentation.ui.sharing.fragments

import android.accounts.Account
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.owncloud.android.R
import com.owncloud.android.databinding.SharePublicDialogBinding
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.domain.capabilities.model.CapabilityBooleanType
import com.owncloud.android.domain.capabilities.model.OCCapability
import com.owncloud.android.domain.sharing.shares.model.OCShare
import com.owncloud.android.domain.utils.Event.EventObserver
import com.owncloud.android.extensions.parseError
import com.owncloud.android.lib.resources.shares.RemoteShare
import com.owncloud.android.lib.resources.status.OwnCloudVersion
import com.owncloud.android.presentation.UIResult
import com.owncloud.android.presentation.viewmodels.capabilities.OCCapabilityViewModel
import com.owncloud.android.presentation.viewmodels.sharing.OCShareViewModel
import com.owncloud.android.ui.dialog.ExpirationDatePickerDialogFragment
import com.owncloud.android.utils.DateUtils
import com.owncloud.android.utils.PreferenceUtils
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import timber.log.Timber
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date

class PublicShareDialogFragment : DialogFragment() {

    /**
     * File to share, received as a parameter in construction time
     */
    private var file: OCFile? = null

    /**
     * OC account holding the file to share, received as a parameter in construction time
     */
    private var account: Account? = null

    /**
     * Existing share to update. If NULL, the dialog will create a new share for file.
     */
    private var publicShare: OCShare? = null

    /**
     * Reference to parent listener
     */
    private var listener: ShareFragmentListener? = null

    /**
     * Capabilities of the server
     */
    private var capabilities: OCCapability? = null

    /**
     * Listener for changes in password switch
     */
    private var onPasswordInteractionListener: OnPasswordInteractionListener? = null

    /**
     * Listener for changes in expiration date switch
     */
    private var onExpirationDateInteractionListener: OnExpirationDateInteractionListener? = null

    private val isSharedFolder: Boolean
        get() = file?.isFolder == true || publicShare?.isFolder == true

    private val isPasswordVisible: Boolean
        get() = view != null && binding.shareViaLinkPasswordValue.inputType and
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

    private// Parse expiration date and convert it to milliseconds
    // remember: format is defined by date picker
    val expirationDateValueInMillis: Long
        get() {
            var publicLinkExpirationDateInMillis: Long = -1
            val expirationDate = binding.shareViaLinkExpirationValue.text.toString()
            if (expirationDate.isNotEmpty()) {
                try {
                    publicLinkExpirationDateInMillis =
                        ExpirationDatePickerDialogFragment.getDateFormat().parse(expirationDate).time
                } catch (e: ParseException) {
                    Timber.e(e, "Error reading expiration date from input field")
                }

            }
            return publicLinkExpirationDateInMillis
        }

    /**
     * Get expiration date imposed by the server, if any
     */
    private val imposedExpirationDate: Long
        get() = if (capabilities?.filesSharingPublicExpireDateEnforced == CapabilityBooleanType.TRUE) {
            DateUtils.addDaysToDate(
                Date(),
                capabilities?.filesSharingPublicExpireDateDays!!
            )
                .time
        } else -1

    private val ocCapabilityViewModel: OCCapabilityViewModel by viewModel {
        parametersOf(
            account?.name
        )
    }

    private val ocShareViewModel: OCShareViewModel by viewModel {
        parametersOf(
            file?.remotePath,
            account?.name
        )
    }

    private var _binding: SharePublicDialogBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            file = it.getParcelable(ARG_FILE)
            account = it.getParcelable(ARG_ACCOUNT)
            publicShare = it.getParcelable(ARG_SHARE)
        }

        check(file != null || publicShare != null) {
            "Both ARG_FILE and ARG_SHARE cannot be NULL"
        }

        setStyle(STYLE_NO_TITLE, 0)
    }

    private fun updating(): Boolean = publicShare != null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SharePublicDialogBinding.inflate(inflater, container, false)
        return binding.root.apply {
            // Allow or disallow touches with other visible windows
            filterTouchesWhenObscured = PreferenceUtils.shouldDisallowTouchesWithOtherVisibleWindows(context)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Get and set the values saved previous to the screen rotation, if any
        if (savedInstanceState != null) {
            val expirationDate = savedInstanceState.getString(KEY_EXPIRATION_DATE)
            if (!expirationDate.isNullOrEmpty()) {
                binding.shareViaLinkExpirationValue.isVisible = true
                binding.shareViaLinkExpirationValue.text = expirationDate
            }
        }

        initTitleAndLabels()
        initPasswordListener()
        initExpirationListener()
        initPasswordFocusChangeListener()
        initPasswordToggleListener()

        binding.saveButton.setOnClickListener { onSaveShareSetting() }
        binding.cancelButton.setOnClickListener { dismiss() }
    }

    private fun initTitleAndLabels() {
        if (updating()) {
            binding.publicShareDialogTitle.setText(R.string.share_via_link_edit_title)
            binding.shareViaLinkNameValue.setText(publicShare?.name)

            when (publicShare?.permissions) {
                RemoteShare.CREATE_PERMISSION_FLAG
                        or RemoteShare.DELETE_PERMISSION_FLAG
                        or RemoteShare.UPDATE_PERMISSION_FLAG
                        or RemoteShare.READ_PERMISSION_FLAG ->
                    binding.shareViaLinkEditPermissionReadAndWrite.isChecked = true
                RemoteShare.CREATE_PERMISSION_FLAG -> binding.shareViaLinkEditPermissionUploadFiles.isChecked = true
                else -> binding.shareViaLinkEditPermissionReadOnly.isChecked = true
            }

            if (publicShare?.isPasswordProtected == true) {
                setPasswordSwitchChecked()
                binding.shareViaLinkPasswordValue.isVisible = true
                binding.shareViaLinkPasswordValue.hint = getString(R.string.share_via_link_default_password)
            }

            if (publicShare?.expirationDate != 0L) {
                setExpirationDateSwitchChecked()
                val formattedDate = ExpirationDatePickerDialogFragment.getDateFormat().format(
                    Date(publicShare?.expirationDate!!)
                )
                binding.shareViaLinkExpirationValue.isVisible = true
                binding.shareViaLinkExpirationValue.text = formattedDate
            }

        } else {
            binding.shareViaLinkNameValue.setText(arguments?.getString(ARG_DEFAULT_LINK_NAME, ""))
        }
    }

    private fun onSaveShareSetting() {
        // Get data filled by user
        val publicLinkName = binding.shareViaLinkNameValue.text.toString()
        var publicLinkPassword: String? = binding.shareViaLinkPasswordValue.text.toString()
        val publicLinkExpirationDateInMillis = expirationDateValueInMillis

        val publicLinkPermissions: Int
        val publicUploadPermission: Boolean

        when (binding.shareViaLinkEditPermissionGroup.checkedRadioButtonId) {
            R.id.shareViaLinkEditPermissionUploadFiles -> {
                publicLinkPermissions = RemoteShare.CREATE_PERMISSION_FLAG
                publicUploadPermission = true
            }
            R.id.shareViaLinkEditPermissionReadAndWrite -> {
                publicLinkPermissions = (RemoteShare.CREATE_PERMISSION_FLAG
                        or RemoteShare.DELETE_PERMISSION_FLAG
                        or RemoteShare.UPDATE_PERMISSION_FLAG
                        or RemoteShare.READ_PERMISSION_FLAG)
                publicUploadPermission = true
            }
            R.id.shareViaLinkEditPermissionReadOnly -> {
                publicLinkPermissions = RemoteShare.READ_PERMISSION_FLAG
                publicUploadPermission = false
            }
            else -> {
                publicLinkPermissions = RemoteShare.READ_PERMISSION_FLAG
                publicUploadPermission = false
            }
        }

        if (!updating()) { // Creating a new public share
            ocShareViewModel.insertPublicShare(
                file?.remotePath!!,
                publicLinkPermissions,
                publicLinkName,
                publicLinkPassword!!,
                publicLinkExpirationDateInMillis,
                false,
                account?.name!!
            )
        } else { // Updating an existing public share
            if (!binding.shareViaLinkPasswordSwitch.isChecked) {
                publicLinkPassword = ""
            } else if (binding.shareViaLinkPasswordValue.text.isEmpty()) {
                // User has not added a new password, so do not update it
                publicLinkPassword = null
            }
            ocShareViewModel.updatePublicShare(
                publicShare?.remoteId!!,
                publicLinkName,
                publicLinkPassword,
                publicLinkExpirationDateInMillis,
                publicLinkPermissions,
                publicUploadPermission,
                account?.name!!
            )
        }
    }

    private fun initPasswordFocusChangeListener() {
        binding.shareViaLinkPasswordValue.setOnFocusChangeListener { v: View, hasFocus: Boolean ->
            if (v.id == R.id.shareViaLinkPasswordValue) {
                onPasswordFocusChanged(hasFocus)
            }
        }
    }

    private fun initPasswordToggleListener() {
        binding.shareViaLinkPasswordValue.setOnTouchListener(object : RightDrawableOnTouchListener() {
            override fun onDrawableTouch(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) {
                    onViewPasswordClick()
                }
                return true
            }
        })
    }

    private abstract class RightDrawableOnTouchListener : View.OnTouchListener {

        private val fuzz = 75

        /**
         * {@inheritDoc}
         */
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            var rightDrawable: Drawable? = null
            if (view is TextView) {
                val drawables = view.compoundDrawables
                if (drawables.size > 2) {
                    rightDrawable = drawables[2]
                }
            }
            if (rightDrawable != null) {
                val x = event.x.toInt()
                val y = event.y.toInt()
                val bounds = rightDrawable.bounds
                if (x >= view.right - bounds.width() - fuzz &&
                    x <= view.right - view.paddingRight + fuzz &&
                    y >= view.paddingTop - fuzz &&
                    y <= view.height - view.paddingBottom + fuzz
                ) {

                    return onDrawableTouch(event)
                }
            }
            return false
        }

        abstract fun onDrawableTouch(event: MotionEvent): Boolean
    }

    /**
     * Handles changes in focus on the text input for the password (basic authorization).
     * When (hasFocus), the button to toggle password visibility is shown.
     * When (!hasFocus), the button is made invisible and the password is hidden.
     *
     * @param hasFocus          'True' if focus is received, 'false' if is lost
     */
    private fun onPasswordFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            showViewPasswordButton()
        } else {
            hidePassword()
            hidePasswordButton()
        }
    }

    /**
     * Called when the eye icon in the password field is clicked.
     *
     * Toggles the visibility of the password in the field.
     */
    fun onViewPasswordClick() {
        if (view != null) {
            if (isPasswordVisible) {
                hidePassword()
            } else {
                showPassword()
            }
            binding.shareViaLinkPasswordValue.setSelection(
                binding.shareViaLinkPasswordValue.selectionStart,
                binding.shareViaLinkPasswordValue.selectionEnd
            )
        }
    }

    private fun showViewPasswordButton() {
        val drawable = if (isPasswordVisible)
            R.drawable.ic_view_black
        else
            R.drawable.ic_hide_black
        binding.shareViaLinkPasswordValue.setCompoundDrawablesWithIntrinsicBounds(0, 0, drawable, 0)
    }

    private fun hidePasswordButton() {
        binding.shareViaLinkPasswordValue.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
    }

    private fun showPassword() {
        binding.shareViaLinkPasswordValue.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        showViewPasswordButton()
    }

    private fun hidePassword() {
        binding.shareViaLinkPasswordValue.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        showViewPasswordButton()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        observeCapabilities()
        observePublicShareCreation()
        observePublicShareEdition()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_EXPIRATION_DATE, binding.shareViaLinkExpirationValue.text.toString())
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            listener = activity as ShareFragmentListener?
        } catch (e: IllegalStateException) {
            throw IllegalStateException(activity?.toString() + " must implement OnShareFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun observeCapabilities() {
        ocCapabilityViewModel.capabilities.observe(this) { event ->
            when (val uiResult = event.peekContent()) {
                is UIResult.Success -> {
                    updateCapabilities(uiResult.data)
                    listener?.dismissLoading()
                }
                is UIResult.Error -> {}
                is UIResult.Loading -> {}
            }
        }
    }

    private fun observePublicShareCreation() {
        ocShareViewModel.publicShareCreationStatus.observe(
            this,
            EventObserver { uiResult ->
                when (uiResult) {
                    is UIResult.Success -> {
                        dismiss()
                    }
                    is UIResult.Error -> {
                        showError(getString(R.string.share_link_file_error), uiResult.error)
                        listener?.dismissLoading()
                    }
                    is UIResult.Loading -> {
                        listener?.showLoading()
                    }
                }
            }
        )
    }

    private fun observePublicShareEdition() {
        ocShareViewModel.publicShareEditionStatus.observe(
            this,
            EventObserver { uiResult ->
                when (uiResult) {
                    is UIResult.Success -> {
                        dismiss()
                    }
                    is UIResult.Error -> {
                        showError(getString(R.string.update_link_file_error), uiResult.error)
                        listener?.dismissLoading()
                    }
                    is UIResult.Loading -> {
                        listener?.showLoading()
                    }
                }
            }
        )
    }

    /**
     * Binds listener for user actions that start any update on a password for the public link
     * to the views receiving the user events.
     *
     */
    private fun initPasswordListener() {
        onPasswordInteractionListener = OnPasswordInteractionListener()
        binding.shareViaLinkPasswordSwitch.setOnCheckedChangeListener(onPasswordInteractionListener)
    }

    /**
     * Listener for user actions that start any update on a password for the public link.
     */
    private inner class OnPasswordInteractionListener : CompoundButton.OnCheckedChangeListener {
        /**
         * Called by R.id.shareViaLinkPasswordSwitch to set or clear the password.
         *
         * @param switchView [SwitchCompat] toggled by the user, R.id.shareViaLinkPasswordSwitch
         * @param isChecked  New switch state.
         */
        override fun onCheckedChanged(switchView: CompoundButton, isChecked: Boolean) {
            if (isChecked) {
                binding.shareViaLinkPasswordValue.isVisible = true
                binding.shareViaLinkPasswordValue.requestFocus()

                // Show keyboard to fill in the password
                val mgr = activity?.getSystemService(
                    Context.INPUT_METHOD_SERVICE
                ) as InputMethodManager?
                mgr?.showSoftInput(binding.shareViaLinkPasswordValue, InputMethodManager.SHOW_IMPLICIT)

            } else {
                binding.shareViaLinkPasswordValue.isVisible = false
                binding.shareViaLinkPasswordValue.text?.clear()
            }
        }
    }

    /**
     * Binds listener for user actions that start any update on a expiration date
     * for the public link to the views receiving the user events.
     *
     */
    private fun initExpirationListener() {
        onExpirationDateInteractionListener = OnExpirationDateInteractionListener()
        binding.shareViaLinkExpirationSwitch.setOnCheckedChangeListener(onExpirationDateInteractionListener)
        binding.shareViaLinkExpirationLabel.setOnClickListener(onExpirationDateInteractionListener)
        binding.shareViaLinkExpirationValue.setOnClickListener(onExpirationDateInteractionListener)
    }

    /**
     * Listener for user actions that start any update on the expiration date for the public link.
     */
    private inner class OnExpirationDateInteractionListener : CompoundButton.OnCheckedChangeListener,
        View.OnClickListener, ExpirationDatePickerDialogFragment.DatePickerFragmentListener {

        /**
         * Called by R.id.shareViaLinkExpirationSwitch to set or clear the expiration date.
         *
         * @param switchView [SwitchCompat] toggled by the user, R.id.shareViaLinkExpirationSwitch
         * @param isChecked  New switch state.
         */
        override fun onCheckedChanged(switchView: CompoundButton, isChecked: Boolean) {
            if (!isResumed) {
                // very important, setCheched(...) is called automatically during
                // Fragment recreation on device rotations
                return
            }

            if (isChecked) {
                // Show calendar to set the expiration date
                val dialog = ExpirationDatePickerDialogFragment.newInstance(
                    expirationDateValueInMillis,
                    imposedExpirationDate
                )
                dialog.setDatePickerListener(this)
                dialog.show(
                    requireActivity().supportFragmentManager,
                    ExpirationDatePickerDialogFragment.DATE_PICKER_DIALOG
                )
            } else {
                binding.shareViaLinkExpirationValue.visibility = View.INVISIBLE
                binding.shareViaLinkExpirationValue.text = ""
            }
        }

        /**
         * Called by R.id.shareViaLinkExpirationLabel or R.id.shareViaLinkExpirationValue
         * to change the current expiration date.
         *
         * @param expirationView Label or value view touched by the user.
         */
        override fun onClick(expirationView: View) {

            // Show calendar to set the expiration date
            val dialog = ExpirationDatePickerDialogFragment.newInstance(
                expirationDateValueInMillis,
                imposedExpirationDate
            )
            dialog.setDatePickerListener(this)
            dialog.show(
                requireActivity().supportFragmentManager,
                ExpirationDatePickerDialogFragment.DATE_PICKER_DIALOG
            )
        }

        /**
         * Update the selected date for the public link
         *
         * @param date date selected by the user
         */
        override fun onDateSet(date: String) {
            binding.shareViaLinkExpirationValue.isVisible = true
            binding.shareViaLinkExpirationValue.text = date
        }

        override fun onCancelDatePicker() {

            // If the date has not been set yet, uncheck the toggle
            if (binding.shareViaLinkExpirationSwitch.isChecked && binding.shareViaLinkExpirationValue.text.isNullOrBlank()) {
                binding.shareViaLinkExpirationSwitch.isChecked = false
            }
        }
    }

    private fun updateCapabilities(capabilities: OCCapability?) {
        this.capabilities = capabilities
        updateInputFormAccordingToServerCapabilities()
    }

    /**
     * Updates the UI according to enforcements and allowances set by the server administrator.
     *
     * Includes:
     * - hide the link name section if multiple public share is not supported, showing the keyboard
     * to fill in the public share name otherwise
     * - hide show file listing option
     * - hide or show the switch to disable the password if it is enforced or not;
     * - hide or show the switch to disable the expiration date it it is enforced or not;
     * - show or hide the switch to allow public uploads if it is allowed or not;
     * - set the default value for expiration date if defined (only if creating a new share).
     */
    private fun updateInputFormAccordingToServerCapabilities() {
        val serverVersion = capabilities?.versionString?.let {
            OwnCloudVersion(it)
        }

        // Show keyboard to fill the public share name
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        if (capabilities?.filesSharingPublicUpload == CapabilityBooleanType.TRUE && isSharedFolder) {
            binding.shareViaLinkEditPermissionGroup.isVisible = true
        }

        // Show file listing option if all the following is true:
        //  - The file to share is a folder
        //  - Upload only is supported by the server version
        //  - Upload only capability is set
        //  - Allow editing capability is set
        if (!(isSharedFolder &&
                    serverVersion?.isPublicSharingWriteOnlySupported == true &&
                    capabilities?.filesSharingPublicSupportsUploadOnly == CapabilityBooleanType.TRUE &&
                    capabilities?.filesSharingPublicUpload == CapabilityBooleanType.TRUE)
        ) {
            binding.shareViaLinkEditPermissionGroup.isVisible = false
        }

        // Show default date enforced by the server, if any
        if (!updating() && capabilities?.filesSharingPublicExpireDateDays ?: 0 > 0) {
            setExpirationDateSwitchChecked()

            val formattedDate = SimpleDateFormat.getDateInstance().format(
                DateUtils.addDaysToDate(
                    Date(),
                    capabilities?.filesSharingPublicExpireDateDays!!
                )
            )

            binding.shareViaLinkExpirationValue.apply {
                isVisible = true
                text = formattedDate
            }
        }

        // Hide expiration date switch if date is enforced to prevent it is removed
        if (capabilities?.filesSharingPublicExpireDateEnforced == CapabilityBooleanType.TRUE) {
            binding.shareViaLinkExpirationLabel.text = getString(R.string.share_via_link_expiration_date_enforced_label)
            binding.shareViaLinkExpirationSwitch.isVisible = false
            binding.shareViaLinkExpirationExplanationLabel.isVisible = true
            binding.shareViaLinkExpirationExplanationLabel.text = getString(
                R.string.share_via_link_expiration_date_explanation_label,
                capabilities?.filesSharingPublicExpireDateDays
            )
        }

        // Set password label when opening the dialog
        if (binding.shareViaLinkEditPermissionReadOnly.isChecked &&
            capabilities?.filesSharingPublicPasswordEnforcedReadOnly == CapabilityBooleanType.TRUE ||
            binding.shareViaLinkEditPermissionReadAndWrite.isChecked &&
            capabilities?.filesSharingPublicPasswordEnforcedReadWrite == CapabilityBooleanType.TRUE ||
            binding.shareViaLinkEditPermissionUploadFiles.isChecked &&
            capabilities?.filesSharingPublicPasswordEnforcedUploadOnly == CapabilityBooleanType.TRUE
        ) {
            setPasswordEnforced()
        }

        // Set password label depending on the checked permission option
        binding.shareViaLinkEditPermissionGroup.setOnCheckedChangeListener { _, checkedId ->
            binding.publicLinkErrorMessage.isVisible = false

            if (checkedId == binding.shareViaLinkEditPermissionReadOnly.id) {
                if (capabilities?.filesSharingPublicPasswordEnforcedReadOnly == CapabilityBooleanType.TRUE) {
                    setPasswordEnforced()
                } else {
                    setPasswordNotEnforced()
                }
            } else if (checkedId == binding.shareViaLinkEditPermissionReadAndWrite.id) {
                if (capabilities?.filesSharingPublicPasswordEnforcedReadWrite == CapabilityBooleanType.TRUE) {
                    setPasswordEnforced()
                } else {
                    setPasswordNotEnforced()
                }
            } else if (checkedId == binding.shareViaLinkEditPermissionUploadFiles.id) {
                if (capabilities?.filesSharingPublicPasswordEnforcedUploadOnly == CapabilityBooleanType.TRUE) {
                    setPasswordEnforced()
                } else {
                    setPasswordNotEnforced()
                }
            }
        }

        // When there's no password enforced for capability
        val hasPasswordEnforcedFor = capabilities?.filesSharingPublicPasswordEnforcedReadOnly ==
                CapabilityBooleanType.TRUE ||
                capabilities?.filesSharingPublicPasswordEnforcedReadWrite == CapabilityBooleanType.TRUE ||
                capabilities?.filesSharingPublicPasswordEnforcedUploadOnly == CapabilityBooleanType.TRUE

        // hide password switch if password is enforced to prevent it is removed
        if (!hasPasswordEnforcedFor && capabilities?.filesSharingPublicPasswordEnforced == CapabilityBooleanType.TRUE) {
            setPasswordEnforced()
        }
    }

    private fun setPasswordNotEnforced() {
        binding.shareViaLinkPasswordLabel.text = getString(R.string.share_via_link_password_label)
        binding.shareViaLinkPasswordSwitch.isVisible = true
        if (!binding.shareViaLinkPasswordSwitch.isChecked) {
            binding.shareViaLinkPasswordValue.isVisible = false
        }
    }

    private fun setPasswordEnforced() {
        binding.shareViaLinkPasswordLabel.text = getString(R.string.share_via_link_password_enforced_label)
        binding.shareViaLinkPasswordSwitch.isChecked = true
        binding.shareViaLinkPasswordSwitch.isVisible = false
        binding.shareViaLinkPasswordValue.isVisible = true
    }

    /**
     * Show error when creating or updating the public share, if any
     */
    private fun showError(genericErrorMessage: String, throwable: Throwable?) {
        binding.publicLinkErrorMessage.text = throwable?.parseError(genericErrorMessage, resources)
        binding.publicLinkErrorMessage.isVisible = true
    }

    private fun setPasswordSwitchChecked() {
        binding.shareViaLinkPasswordSwitch.apply {
            setOnCheckedChangeListener(null)
            isChecked = true
            setOnCheckedChangeListener(onPasswordInteractionListener)
        }
    }

    private fun setExpirationDateSwitchChecked() {
        binding.shareViaLinkExpirationSwitch.setOnCheckedChangeListener(null)
        binding.shareViaLinkExpirationSwitch.isChecked = true
        binding.shareViaLinkExpirationSwitch.setOnCheckedChangeListener(onExpirationDateInteractionListener)
    }

    companion object {
        /**
         * The fragment initialization parameters
         */
        private const val ARG_FILE = "FILE"
        private const val ARG_SHARE = "SHARE"
        private const val ARG_ACCOUNT = "ACCOUNT"
        private const val ARG_DEFAULT_LINK_NAME = "DEFAULT_LINK_NAME"
        private const val KEY_EXPIRATION_DATE = "EXPIRATION_DATE"

        /**
         * Create a new instance of PublicShareDialogFragment, providing fileToShare as an argument.
         *
         * Dialog shown this way is intended to CREATE a new public share.
         *
         * @param   fileToShare     File to share with a new public share.
         */
        fun newInstanceToCreate(
            fileToShare: OCFile,
            account: Account,
            defaultLinkName: String
        ): PublicShareDialogFragment {
            val args = Bundle().apply {
                putParcelable(ARG_FILE, fileToShare)
                putParcelable(ARG_ACCOUNT, account)
                putString(ARG_DEFAULT_LINK_NAME, defaultLinkName)
            }

            return PublicShareDialogFragment().apply { arguments = args }
        }

        /**
         * Update an instance of PublicShareDialogFragment, providing fileToShare, publicShare as arguments.
         *
         * Dialog shown this way is intended to UPDATE an existing public share.
         *
         * @param   publicShare           Public share to update.
         */
        fun newInstanceToUpdate(
            fileToShare: OCFile,
            account: Account,
            publicShare: OCShare
        ): PublicShareDialogFragment {
            val args = Bundle().apply {
                putParcelable(ARG_FILE, fileToShare)
                putParcelable(ARG_ACCOUNT, account)
                putParcelable(ARG_SHARE, publicShare)
            }

            return PublicShareDialogFragment().apply { arguments = args }
        }
    }
}
