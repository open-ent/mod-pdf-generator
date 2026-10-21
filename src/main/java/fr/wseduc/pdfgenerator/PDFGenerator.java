/*
 * Copyright © WebServices pour l'Éducation, 2014
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.wseduc.pdfgenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;

import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import org.vertx.java.busmods.BusModBase;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.xhtmlrenderer.pdf.ITextUserAgent;

public class PDFGenerator extends BusModBase implements Handler<Message<JsonObject>> {

	private String baseUrl = "";
	private int connectTimeout;
	private int readTimeout;

	/**
	 * Verticle start method.
	 */
	@Override
	public void start(){
		super.start();
		final String address = config.getString("address", "entcore.pdf.generator");
		logger.info("Starting PdfGenerator - address : " + address);
		vertx.eventBus().localConsumer(address, this);
		baseUrl = config.getString("baseUrl", "");
		connectTimeout = config.getInteger("connectTimeout", 5000);
		readTimeout = config.getInteger("readTimeout", 15000);
	}

	/**
	 * Le rendu télécharge les images du document de façon bloquante : il tourne donc sur un fil
	 * de travail, jamais sur le fil d'événements. Sur le fil d'événements, une image servie par
	 * ce même serveur ne pouvait pas être servie (le fil qui devait répondre était celui qui
	 * attendait) et tout le nœud restait figé jusqu'à l'expiration de la connexion.
	 * Les rendus restent séquentiels ({@code ordered}), comme auparavant.
	 */
	@Override
	public void handle(Message<JsonObject> message) {
		vertx.executeBlocking(promise -> {
			render(message);
			promise.complete();
		}, true, null);
	}

	private void render(Message<JsonObject> message) {
		final String encoding = message.body().getString("encoding", "UTF-8");
		ByteArrayOutputStream binaryOutput = new ByteArrayOutputStream();

		try {
			String content = new String(message.body().getBinary("content"), encoding);

			if(content == null || content.trim().isEmpty()){
				sendError(message, "invalid.content");
				return;
			}

			//Actual pdf rendering
			final ITextRenderer renderer = newRenderer();
			renderer.setDocumentFromString(content, message.body().getString("baseUrl", baseUrl));
			renderer.layout();
			renderer.createPDF(binaryOutput);

			sendOK(message, new JsonObject().put("content", binaryOutput.toByteArray()));
		} catch (UnsupportedEncodingException encexp) {
			sendError(message, "invalid.encoding", encexp);
		} catch (Exception exc) {
			sendError(message, "error.while.rendering", exc);
		} finally {
			try {
				binaryOutput.close();
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
		}
	}

	/**
	 * Moteur de rendu dont les téléchargements d'images ont des délais : une image injoignable
	 * est omise au lieu de bloquer le rendu (et le fil qui l'exécute) indéfiniment.
	 */
	private ITextRenderer newRenderer() {
		final ITextRenderer renderer = new ITextRenderer();
		final ITextUserAgent userAgent = new ITextUserAgent(renderer.getOutputDevice()) {
			@Override
			protected InputStream resolveAndOpenStream(String uri) {
				final String resolved = resolveURI(uri);
				try {
					final URLConnection connection = new URL(resolved).openConnection();
					connection.setConnectTimeout(connectTimeout);
					connection.setReadTimeout(readTimeout);
					return connection.getInputStream();
				} catch (Exception e) {
					logger.warn("PdfGenerator : cannot load resource " + resolved + " : " + e.getMessage());
					return null;
				}
			}
		};
		userAgent.setSharedContext(renderer.getSharedContext());
		renderer.getSharedContext().setUserAgentCallback(userAgent);
		renderer.getSharedContext().getCss().setUserAgentCallback(userAgent);
		return renderer;
	}

}
