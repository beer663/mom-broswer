return res
    }

    fun onSlowScriptPrompt(
        geckoSession: GeckoSession?, title: String?, reportAction: GeckoResult<SlowScriptResponse?>
    ) {
        val activity: Activity = webEngine.callback?.getActivity() ?: return
        val builder = AlertDialog.Builder(activity)
        builder
            .setTitle(title)
            .setNegativeButton(
                activity.getString(R.string.wait)
            ) { dialog, which -> reportAction.complete(SlowScriptResponse.CONTINUE) }
            .setPositiveButton(
                activity.getString(R.string.stop)
            ) { dialog, which -> reportAction.complete(SlowScriptResponse.STOP) }
        val dialog = builder.create()
        dialog.show()
    }

    private fun addMediaSpinner(
        context: Context,
        container: ViewGroup?,
        sources: Array<PermissionDelegate.MediaSource>,
        sourceNames: Array<String>?
    ): Spinner? {
        val adapter: ArrayAdapter<PermissionDelegate.MediaSource> = object :
            ArrayAdapter<PermissionDelegate.MediaSource>(
                context,
                android.R.layout.simple_spinner_item
            ) {
            private fun convertView(position: Int, view: View): View {
                val item = getItem(position)
                (view as TextView).text = sourceNames?.get(position) ?: item!!.name
                return view
            }

            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                return convertView(position, super.getView(position, view, parent))
            }

            override fun getDropDownView(position: Int, view: View?, parent: ViewGroup): View {
                return convertView(position, super.getDropDownView(position, view, parent))
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        adapter.addAll(*sources)
        val spinner = Spinner(context)
        spinner.adapter = adapter
        spinner.setSelection(0)
        container!!.addView(spinner)
        return spinner
    }

    fun onMediaPrompt(
        session: GeckoSession?,
        title: String?,
        video: Array<PermissionDelegate.MediaSource>?,
        audio: Array<PermissionDelegate.MediaSource>?,
        videoNames: Array<String>?,
        audioNames: Array<String>?,
        callback: MediaCallback
    ) {
        val activity: Activity? = webEngine.callback?.getActivity()
        if (activity == null || video == null && audio == null) {
            callback.reject()
            return
        }
        val builder = AlertDialog.Builder(activity)
        val container = addStandardLayout(builder, title,  null)
        val videoSpinner: Spinner? = if (video != null) {
            addMediaSpinner(builder.context, container, video, videoNames)
        } else {
            null
        }
        val audioSpinner: Spinner? = if (audio != null) {
            addMediaSpinner(builder.context, container, audio, audioNames)
        } else {
            null
        }
        builder
            .setNegativeButton(android.R.string.cancel,  null)
            .setPositiveButton(
                android.R.string.ok
            ) { dialog, which ->
                val video =
                    if (videoSpinner != null) videoSpinner.selectedItem as PermissionDelegate.MediaSource else null
                val audio =
                    if (audioSpinner != null) audioSpinner.selectedItem as PermissionDelegate.MediaSource else null
                callback.grant(video, audio)
            }
        val dialog = builder.create()
        dialog.setOnDismissListener { callback.reject() }
        dialog.show()
    }

    fun onMediaPrompt(
        session: GeckoSession?,
        title: String?,
        video: Array<PermissionDelegate.MediaSource>?,
        audio: Array<PermissionDelegate.MediaSource>?,
        callback: MediaCallback
    ) {
        onMediaPrompt(session, title, video, audio, null, null, callback)
    }
}
